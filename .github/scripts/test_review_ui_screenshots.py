"""Selection and comment lifecycle tests; GitHub writes use an in-memory API."""
import copy
from pathlib import Path
import tempfile
import unittest

from PIL import Image, PngImagePlugin
from review_ui_screenshots import MARKER, render, select, upsert


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.current = Path(self.temp.name) / 'pr'
        self.baseline = Path(self.temp.name) / 'main'

    def image(self, directory, scenario='scenario', size='1024x720.png', color='red', dimensions=(3, 2), metadata=False):
        path = directory / scenario / size
        path.parent.mkdir(parents=True, exist_ok=True)
        info = PngImagePlugin.PngInfo()
        if metadata:
            info.add_text('ignored', 'different encoding')
        Image.new('RGBA', dimensions, color).save(path, pnginfo=info)
        return path

    def test_decoded_content_ignores_png_metadata_and_encoding(self):
        self.image(self.current, metadata=True)
        self.image(self.baseline)
        self.assertEqual(select(self.current, self.baseline, set()), ({}, []))

    def test_new_changed_dimensions_pixels_and_removed(self):
        self.image(self.current, 'new')
        self.image(self.current, 'pixels', color='blue')
        self.image(self.baseline, 'pixels')
        self.image(self.current, 'dimensions', dimensions=(4, 2))
        self.image(self.baseline, 'dimensions')
        self.image(self.baseline, 'removed')
        selected, removed = select(self.current, self.baseline, set())
        self.assertEqual(selected['new']['reasons'], {'new'})
        self.assertEqual(selected['pixels']['reasons'], {'changed'})
        self.assertEqual(selected['dimensions']['reasons'], {'changed'})
        self.assertEqual(removed, ['removed/1024x720.png'])

    def test_changed_test_ownership_includes_unchanged_pixels(self):
        self.image(self.current)
        self.image(self.baseline)
        source = 'app-desktop/src/test/kotlin/UnpredictableTest.kt'
        (self.current / 'scenario/capture-source.txt').write_text(source + '\n')
        selected, _ = select(self.current, self.baseline, {source})
        self.assertEqual(selected['scenario']['reasons'], {'test-modified'})
        self.assertFalse(select(self.current, self.baseline, {'SomethingElse.kt'})[0])

    def test_changed_nondefault_size_selects_entire_scenario(self):
        for directory in [self.current, self.baseline]:
            self.image(directory)
            self.image(directory, size='2560x1440.png')
        self.image(self.current, size='2560x1440.png', color='blue')
        body = render(self.current, self.baseline, set(), 'owner/repo', 'abc123', 36, (10, 2))
        expanded, collapsed = body.split('<details><summary>Other resolutions</summary>')
        self.assertEqual(expanded.count('!['), 1)
        self.assertIn('/abc123/36/scenario/1024x720.png', expanded)
        self.assertIn('/2560x1440.png', collapsed)
        self.assertIn('changed', expanded)

    def test_all_six_sizes_only_one_expanded_image_per_scenario(self):
        for scenario in ['arbitrary-checkpoint', 'another']:
            for size in ['1024x720', '1024x768', '1280x720', '1680x1050', '1920x1080', '2560x1440']:
                self.image(self.current, scenario, size + '.png')
        body = render(self.current, self.baseline, set(), 'o/r', 'sha', 36, (1, 1))
        self.assertEqual(body.count('!['), 12)
        self.assertEqual(body.count('<details><summary>Other resolutions</summary>'), 2)
        self.assertIn('Main baseline unavailable', body)
        for section in body.split('### ')[1:]:
            self.assertEqual(section.split('<details>')[0].count('!['), 1)

    def test_fallback_resolution_clean_and_removed_only(self):
        self.image(self.current, size='custom.png')
        body = render(self.current, self.baseline, set(), 'o/r', 'sha', 36, (1, 1))
        self.assertIn('/custom.png', body)
        self.image(self.baseline, size='custom.png')
        self.image(self.baseline, 'removed')
        body = render(self.current, self.baseline, set(), 'o/r', 'sha', 36, (1, 1))
        self.assertIn('No visual differences', body)
        self.assertIn('Removed captures', body)
        self.assertNotIn('![', body)


class FakeAPI:
    def __init__(self, comments=(), heads=('head', 'head')):
        self.comments = copy.deepcopy(list(comments))
        self.heads = iter(heads)
        self.writes = []

    def pages(self, path):
        return self.comments

    def request(self, path, method='GET', data=None):
        if method == 'GET':
            return {'state': 'open', 'head': {'sha': next(self.heads)}}
        self.writes.append((path, method, data))
        if method == 'POST':
            self.comments.append({'id': 5, 'user': {'type': 'Bot'}, **data})
        else:
            self.comments[0].update(data)


class CommentTests(unittest.TestCase):
    def body(self, run):
        return f'{MARKER}\n<!-- capture-run: {run[0]} {run[1]} -->\nimages'

    def test_create_then_update_same_comment_and_idempotent_retry(self):
        api = FakeAPI(heads=['head'] * 6)
        first = self.body((10, 1))
        self.assertEqual(upsert(api, 36, first, (10, 1), 'head'), 'created')
        self.assertEqual(upsert(api, 36, first, (10, 1), 'head'), 'unchanged')
        self.assertEqual(upsert(api, 36, self.body((10, 2)), (10, 2), 'head'), 'updated')
        self.assertEqual(len(api.comments), 1)
        self.assertEqual(api.writes[-1][0], '/issues/comments/5')

    def test_stale_run_or_attempt_cannot_replace_newer_comment(self):
        for run in [(9, 20), (10, 1)]:
            api = FakeAPI([{'id': 1, 'user': {'type': 'Bot'}, 'body': self.body((10, 2))}])
            self.assertEqual(upsert(api, 36, self.body(run), run, 'head'), 'stale')
            self.assertFalse(api.writes)

    def test_old_head_and_push_during_selection_skip_comment(self):
        for heads in [('new-head',), ('head', 'new-head')]:
            api = FakeAPI(heads=heads)
            self.assertEqual(upsert(api, 36, self.body((1, 1)), (1, 1), 'head'), 'obsolete-head')
            self.assertFalse(api.writes)

    def test_human_comment_with_marker_is_not_overwritten(self):
        api = FakeAPI([{'id': 1, 'user': {'type': 'User'}, 'body': self.body((100, 1))}])
        self.assertEqual(upsert(api, 36, self.body((1, 1)), (1, 1), 'head'), 'created')
        self.assertEqual(api.writes[0][1], 'POST')


if __name__ == '__main__':
    unittest.main()
