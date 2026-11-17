"""Exercise the production publisher against isolated bare Git repositories."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('publish-ui-screenshots.sh').resolve()


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / 'remote.git'
        subprocess.run(['git', 'init', '--bare', '--quiet', str(self.remote)], check=True)
        self.source = self.root / 'captures'
        (self.source / 'scenario').mkdir(parents=True)
        (self.source / 'scenario/test.png').write_bytes(b'fixture image; publisher copies bytes')

    def command(self, target, run, attempt=1, **extra):
        return subprocess.Popen(['bash', str(SCRIPT), str(self.remote), target, str(self.source)],
                                env={**os.environ, 'GITHUB_RUN_NUMBER': str(run),
                                     'GITHUB_RUN_ATTEMPT': str(attempt), **extra},
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def finish(self, process):
        out, err = process.communicate(timeout=60)
        self.assertEqual(process.returncode, 0, out + err)

    def read(self, path):
        return subprocess.check_output(['git', '--git-dir', str(self.remote), 'show', f'pr-assets:{path}'], text=True)

    def test_main_pr_preservation_stale_runs_attempts_and_idempotency(self):
        self.finish(self.command('main', 10, 2))
        self.finish(self.command('36', 11))
        self.finish(self.command('main', 9, 5))
        self.finish(self.command('main', 10, 1))
        self.assertEqual(self.read('main/.workflow-run'), '10 2\n')
        self.assertEqual(self.read('36/.workflow-run'), '11 1\n')
        before = self.read('36/scenario/test.png')
        (self.source / 'scenario/test.png').unlink()
        (self.source / 'scenario/new.png').write_text('replacement')
        self.finish(self.command('36', 12))
        self.assertEqual(self.read('main/scenario/test.png'), before)
        files = subprocess.check_output(['git', '--git-dir', str(self.remote), 'ls-tree', '-r', '--name-only', 'pr-assets'], text=True)
        self.assertNotIn('36/scenario/test.png', files)
        output = self.root / 'outputs'
        self.finish(self.command('36', 12, GITHUB_OUTPUT=str(output)))
        self.assertIn('published=true\ncommit=', output.read_text())
        self.finish(self.command('36', 11, GITHUB_OUTPUT=str(output)))
        self.assertTrue(output.read_text().endswith('published=false\n'))

    def test_concurrent_push_retries_and_preserves_both_targets(self):
        # Pause each process's first push until both have read the same branch tip.
        self.finish(self.command('main', 1))
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        real_git = shutil.which('git')
        wrapper = bin_dir / 'git'
        wrapper.write_text('''#!/usr/bin/env bash
if [[ $1 == push && ! -f "$BARRIER/$TARGET.ready" ]]; then
    touch "$BARRIER/$TARGET.ready"
    for i in {1..200}; do
        [[ -f "$BARRIER/a.ready" && -f "$BARRIER/b.ready" ]] && break
        sleep .05
    done
fi
exec "''' + real_git + '''" "$@"
''')
        wrapper.chmod(0o755)
        env = {'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'], 'BARRIER': str(self.root)}
        a = self.command('36', 10, TARGET='a', **env)
        b = self.command('37', 11, TARGET='b', **env)
        self.finish(a)
        self.finish(b)
        self.assertEqual(self.read('36/.workflow-run'), '10 1\n')
        self.assertEqual(self.read('37/.workflow-run'), '11 1\n')
        self.assertEqual(self.read('main/.workflow-run'), '1 1\n')

    def test_concurrent_same_target_cannot_end_with_older_capture(self):
        self.finish(self.command('36', 20))
        a = self.command('36', 21)
        b = self.command('36', 22)
        self.finish(a)
        self.finish(b)
        self.assertEqual(self.read('36/.workflow-run'), '22 1\n')

    def test_invalid_target_cannot_escape_directory(self):
        process = self.command('../main', 1)
        process.communicate(timeout=10)
        self.assertNotEqual(process.returncode, 0)


if __name__ == '__main__':
    unittest.main()
