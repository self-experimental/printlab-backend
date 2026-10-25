"""Check the deployment boundary and recovery without Docker or SSH access."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('receiver', Path(__file__).parents[1] / 'receive-deploy.py')
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = self.root / 'compose.env'
        self.env.write_text('BACKEND_IMAGE=old-backend\nFRONTEND_IMAGE=old-frontend\n')
        self.payload = dict(revision='a' * 40, digest='sha256:' + 'b' * 64,
                            registry_user='owner', registry_token='secret_' + 'x' * 40,
                            run_number=10, run_attempt=1)
        self.stack = []
        for name, value in dict(STATE=self.root / 'state', ENV_FILE=self.env).items():
            p = patch.object(receiver, name, value)
            p.start()
            self.addCleanup(p.stop)

    def command(self, args, **kwargs):
        self.stack.append(args)
        if 'inspect' in args:
            return 'linux/amd64' if '{{.Os}}/{{.Architecture}}' in args else self.payload['revision']
        return ''

    def test_rejects_commands_tags_arbitrary_images_and_invalid_sequences(self):
        for field, value in [('revision', '$(id)'), ('digest', 'latest'),
                             ('digest', 'sha256:' + 'b' * 64 + '\nROOT=1'),
                             ('run_number', True), ('run_attempt', -1),
                             ('registry_user', '--password=bad'), ('registry_token', 'bad\nsecret')]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                receiver.validate('backend', {**self.payload, field: value})
        with self.assertRaises(ValueError):
            receiver.validate('../../etc', self.payload)

    def test_success_updates_only_requested_component_and_records_digest(self):
        with patch.object(receiver, 'run', side_effect=self.command), patch.object(receiver, 'check') as check:
            receiver.deploy('backend', self.payload)
        values = receiver.read_env()
        self.assertTrue(values['BACKEND_IMAGE'].endswith('@' + self.payload['digest']))
        self.assertEqual('old-frontend', values['FRONTEND_IMAGE'])
        check.assert_called_once_with('backend')
        state = json.loads((receiver.STATE / 'backend.json').read_text())
        self.assertEqual('old-backend', state['previous_image'])
        self.assertEqual(0o600, self.env.stat().st_mode & 0o777)
        login = next(args for args in self.stack if 'login' in args)
        self.assertNotIn(self.payload['registry_token'], ' '.join(login))

    def test_failed_smoke_restores_previous_image_without_changing_other_service(self):
        with patch.object(receiver, 'run', side_effect=self.command), \
                patch.object(receiver, 'check', side_effect=RuntimeError('broken slicer')), \
                patch.object(receiver.signal, 'signal'), self.assertRaises(RuntimeError):
            receiver.deploy('backend', self.payload)
        self.assertEqual(dict(BACKEND_IMAGE='old-backend', FRONTEND_IMAGE='old-frontend'), receiver.read_env())
        self.assertFalse((receiver.STATE / 'backend.json').exists())
        self.assertEqual(2, sum('compose' in args for args in self.stack))

    def test_wrong_revision_never_restarts_service(self):
        def wrong_revision(args, **kwargs):
            return 'c' * 40 if 'inspect' in args else ''
        with patch.object(receiver, 'run', side_effect=wrong_revision), \
                patch.object(receiver, 'compose') as compose, self.assertRaises(ValueError):
            receiver.deploy('backend', self.payload)
        compose.assert_not_called()
        self.assertEqual('old-backend', receiver.read_env()['BACKEND_IMAGE'])

    def test_stale_workflow_cannot_replace_a_newer_release(self):
        receiver.STATE.mkdir()
        (receiver.STATE / 'frontend.json').write_text(json.dumps(dict(run_number=11, run_attempt=1)))
        with patch.object(receiver, 'run') as run, self.assertRaises(ValueError):
            receiver.deploy('frontend', self.payload)
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
