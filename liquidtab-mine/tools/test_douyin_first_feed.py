import unittest

from check_douyin_first_feed import check


PID = 21351
HIT = "LiquidTab: first_feed_binding native_defer=true action=immediate"
PROBE = "LiquidTab: coldstart_probe t=0ms page=2312x2504 h=2504 intro=279 bar=1195"
TRACE = "NukeApp-OSS: io.github.nuke.oss.fork: feature=KeepFeedOnReturn trace_gated event="
PRELOAD = TRACE + "feed_results H0 items=1"
LOAD_MORE = TRACE + "feed_results aE items=17"
LANDING = TRACE + "pager_settle item=0 smooth=false always=true"
NEXT_PAGE = TRACE + "pager_settle item=1 smooth=false always=false"


def line(message, pid=PID):
    return f"11-21 09:58:31.713 {pid} {pid} I {message}\n"


class LandingPageTest(unittest.TestCase):
    def test_immediate_binding_before_load_more(self):
        self.assertEqual(check(map(line, [HIT, LANDING, PRELOAD, PROBE, LOAD_MORE, NEXT_PAGE]), PID)[0], 0)

    def test_full_height_but_empty_controls(self):
        blank = PROBE.replace("intro=279 bar=1195", "intro=0 bar=0")
        later = PROBE.replace("t=0ms", "t=2971ms")
        self.assertEqual(check(map(line, [LANDING, PRELOAD, blank, LOAD_MORE, later]), PID)[0], 1)

    def test_evidence_from_another_process(self):
        trace = [line(HIT, 15463), line(LANDING), line(PRELOAD), line(PROBE), line(LOAD_MORE)]
        self.assertEqual(check(trace, PID)[0], 2)

    def test_ambiguous_or_partial_runs(self):
        for messages in (
            [HIT, PRELOAD, PROBE, LOAD_MORE],
            [HIT, LANDING, PRELOAD, NEXT_PAGE, PROBE, LOAD_MORE],
            [HIT, LANDING, PRELOAD, LOAD_MORE, PROBE],
            [HIT, LANDING, PRELOAD, PROBE],
            [HIT, LANDING, PRELOAD, PROBE, LOAD_MORE, PROBE],
            [HIT, LANDING, PRELOAD, PROBE + " | page=2312x2504 h=2504 intro=0 bar=0", LOAD_MORE],
        ):
            with self.subTest(messages=messages):
                self.assertEqual(check(map(line, messages), PID)[0], 2)


if __name__ == "__main__":
    unittest.main()
