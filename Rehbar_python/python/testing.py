from __future__ import annotations

import argparse
import csv
import json
from dataclasses import dataclass, asdict
from typing import List, Optional, Dict, Any
from collections import Counter, defaultdict

# Change this import if needed
from intent_classifier import IntentClassifier


@dataclass
class TestCase:
    text: str
    expected: str
    notes: str = ""
    strict: bool = True
    allowed: Optional[List[str]] = None


def build_test_cases() -> List[TestCase]:
    cases: List[TestCase] = []

    # ── CHAT / small talk ─────────────────────────────────────────────────────
    cases += [
        TestCase("hello", "CHAT"),
        TestCase("hey rehbar", "CHAT"),
        TestCase("good morning", "CHAT"),
        TestCase("how are you", "CHAT"),
        TestCase("thanks", "CHAT"),
        TestCase("who made you", "CHAT"),
        TestCase("what is your name", "CHAT"),
        TestCase("okay", "CHAT"),
        TestCase("cool", "CHAT"),
        TestCase("alright then", "CHAT"),
    ]

    # ── OPEN_APP ──────────────────────────────────────────────────────────────
    cases += [
        TestCase("open chrome", "OPEN_APP"),
        TestCase("launch vscode", "OPEN_APP"),
        TestCase("start spotify", "OPEN_APP"),
        TestCase("run notepad", "OPEN_APP"),
        TestCase("open calculator", "OPEN_APP"),
        TestCase("please open task manager", "OPEN_APP"),
        TestCase("hey rehbar open command prompt", "OPEN_APP"),
        TestCase("bring up explorer", "OPEN_APP"),
        TestCase("fire up vlc", "OPEN_APP"),
        TestCase("open my code editor", "OPEN_APP", strict=False, allowed=["OPEN_APP", "UNKNOWN"]),
    ]

    # ── CLOSE_APP ─────────────────────────────────────────────────────────────
    cases += [
        TestCase("close chrome", "CLOSE_APP"),
        TestCase("quit spotify", "CLOSE_APP"),
        TestCase("exit notepad", "CLOSE_APP"),
        TestCase("kill discord", "CLOSE_APP"),
        TestCase("stop vlc", "CLOSE_APP"),
        TestCase("terminate the browser", "CLOSE_APP"),
        TestCase("shut down explorer", "CLOSE_APP"),
        TestCase("close the app", "CLOSE_APP", strict=False, allowed=["CLOSE_APP", "UNKNOWN"]),
        TestCase("force close the window", "CLOSE_APP"),
        TestCase("end this program", "CLOSE_APP"),
    ]

    # ── OPEN_SITE ─────────────────────────────────────────────────────────────
    cases += [
        TestCase("go to github", "OPEN_SITE"),
        TestCase("open youtube", "OPEN_SITE"),
        TestCase("navigate to gmail", "OPEN_SITE"),
        TestCase("visit wikipedia", "OPEN_SITE"),
        TestCase("take me to reddit", "OPEN_SITE"),
        TestCase("open google maps", "OPEN_SITE"),
        TestCase("browse to linkedin", "OPEN_SITE"),
        TestCase("open chatgpt", "OPEN_SITE"),
        TestCase("go to netflix", "OPEN_SITE"),
        TestCase("visit apple website", "OPEN_SITE"),
    ]

    # ── WEB_SEARCH ────────────────────────────────────────────────────────────
    cases += [
        TestCase("search for python decorators", "WEB_SEARCH"),
        TestCase("google the weather today", "WEB_SEARCH"),
        TestCase("look up java streams", "WEB_SEARCH"),
        TestCase("find information about black holes", "WEB_SEARCH"),
        TestCase("who is the president of pakistan", "WEB_SEARCH"),
        TestCase("what is machine learning", "WEB_SEARCH"),
        TestCase("how to cook pasta", "WEB_SEARCH"),
        TestCase("search nearby restaurants", "WEB_SEARCH"),
        TestCase("look up stock prices today", "WEB_SEARCH"),
        TestCase("find the latest tech news", "WEB_SEARCH"),
    ]

    # ── SYSTEM_INFO ───────────────────────────────────────────────────────────
    cases += [
        TestCase("what time is it", "SYSTEM_INFO"),
        TestCase("tell me the time", "SYSTEM_INFO"),
        TestCase("what is the date", "SYSTEM_INFO"),
        TestCase("show battery level", "SYSTEM_INFO"),
        TestCase("how much ram is used", "SYSTEM_INFO"),
        TestCase("current cpu usage", "SYSTEM_INFO"),
        TestCase("check disk space", "SYSTEM_INFO"),
        TestCase("what day is it today", "SYSTEM_INFO"),
        TestCase("show system information", "SYSTEM_INFO"),
        TestCase("how much battery is left", "SYSTEM_INFO"),
    ]

    # ── SET_ALARM ─────────────────────────────────────────────────────────────
    cases += [
        TestCase("set an alarm for 7 am", "SET_ALARM"),
        TestCase("wake me up at 6", "SET_ALARM"),
        TestCase("set a timer for 10 minutes", "SET_ALARM"),
        TestCase("remind me at 3 pm to drink water", "SET_ALARM"),
        TestCase("alarm for tomorrow at 8", "SET_ALARM"),
        TestCase("new alarm at midnight", "SET_ALARM"),
        TestCase("set reminder for gym", "SET_ALARM"),
        TestCase("timer for 25 minutes", "SET_ALARM"),
        TestCase("wake me in two hours", "SET_ALARM"),
        TestCase("set a reminder at 6 pm", "SET_ALARM"),
    ]

    # ── CREATE_FILE ───────────────────────────────────────────────────────────
    cases += [
        TestCase("create a folder called projects", "CREATE_FILE"),
        TestCase("make a new directory", "CREATE_FILE"),
        TestCase("new folder named ai", "CREATE_FILE"),
        TestCase("create a text file", "CREATE_FILE"),
        TestCase("make a backup folder", "CREATE_FILE"),
        TestCase("generate file named notes", "CREATE_FILE"),
        TestCase("create documents folder", "CREATE_FILE"),
        TestCase("make a new empty file", "CREATE_FILE"),
        TestCase("create a readme file", "CREATE_FILE"),
        TestCase("create folder on desktop", "CREATE_FILE"),
    ]

    # ── DELETE_FILE ───────────────────────────────────────────────────────────
    cases += [
        TestCase("delete the file report", "DELETE_FILE"),
        TestCase("remove the backup folder", "DELETE_FILE"),
        TestCase("erase temp file", "DELETE_FILE"),
        TestCase("trash this directory", "DELETE_FILE"),
        TestCase("delete old backups", "DELETE_FILE"),
        TestCase("get rid of the log file", "DELETE_FILE"),
        TestCase("wipe the folder archive", "DELETE_FILE"),
        TestCase("remove old documents", "DELETE_FILE"),
        TestCase("delete downloads folder", "DELETE_FILE"),
        TestCase("permanently delete this file", "DELETE_FILE"),
    ]

    # ── RENAME_FILE ───────────────────────────────────────────────────────────
    cases += [
        TestCase("rename file report to final", "RENAME_FILE"),
        TestCase("change the folder name to work", "RENAME_FILE"),
        TestCase("rename this to backup", "RENAME_FILE"),
        TestCase("modify file name to notes", "RENAME_FILE"),
        TestCase("rename my resume to cv", "RENAME_FILE"),
        TestCase("change file test to main", "RENAME_FILE"),
        TestCase("give this file a new name", "RENAME_FILE"),
        TestCase("rename draft to final version", "RENAME_FILE"),
        TestCase("relabel this folder", "RENAME_FILE"),
        TestCase("update the file name", "RENAME_FILE"),
    ]

    # ── Ambiguous boundaries: allowed outcomes ────────────────────────────────
    cases += [
        TestCase("open google", "OPEN_SITE", strict=False, allowed=["OPEN_SITE", "WEB_SEARCH"]),
        TestCase("search google", "WEB_SEARCH", strict=False, allowed=["WEB_SEARCH", "OPEN_SITE"]),
        TestCase("open youtube and search for lofi", "OPEN_SITE", strict=False, allowed=["OPEN_SITE", "WEB_SEARCH"]),
        TestCase("what is the time in karachi", "WEB_SEARCH", strict=False, allowed=["WEB_SEARCH", "SYSTEM_INFO"]),
        TestCase("check my system", "SYSTEM_INFO", strict=False, allowed=["SYSTEM_INFO", "UNKNOWN"]),
        TestCase("create report", "CREATE_FILE", strict=False, allowed=["CREATE_FILE", "UNKNOWN"]),
        TestCase("delete report", "DELETE_FILE", strict=False, allowed=["DELETE_FILE", "UNKNOWN"]),
        TestCase("rename report", "RENAME_FILE", strict=False, allowed=["RENAME_FILE", "UNKNOWN"]),
        TestCase("close the browser", "CLOSE_APP", strict=False, allowed=["CLOSE_APP", "UNKNOWN"]),
        TestCase("open the browser", "OPEN_APP", strict=False, allowed=["OPEN_APP", "OPEN_SITE"]),
    ]

    # ── Voice / ASR-like variants ─────────────────────────────────────────────
    cases += [
        TestCase("rehbar open krom", "OPEN_APP", strict=False, allowed=["OPEN_APP", "UNKNOWN"]),
        TestCase("open chrom", "OPEN_APP", strict=False, allowed=["OPEN_APP", "UNKNOWN"]),
        TestCase("go too github", "OPEN_SITE", strict=False, allowed=["OPEN_SITE", "UNKNOWN"]),
        TestCase("serch for pythin tutoriels", "WEB_SEARCH", strict=False, allowed=["WEB_SEARCH", "UNKNOWN"]),
        TestCase("set an allarm for seven am", "SET_ALARM", strict=False, allowed=["SET_ALARM", "UNKNOWN"]),
        TestCase("wut time is it", "SYSTEM_INFO", strict=False, allowed=["SYSTEM_INFO", "WEB_SEARCH", "UNKNOWN"]),
        TestCase("delite the bakup folder", "DELETE_FILE", strict=False, allowed=["DELETE_FILE", "UNKNOWN"]),
        TestCase("renaim my file to final", "RENAME_FILE", strict=False, allowed=["RENAME_FILE", "UNKNOWN"]),
        TestCase("make new foulder", "CREATE_FILE", strict=False, allowed=["CREATE_FILE", "UNKNOWN"]),
        TestCase("cloze chrome", "CLOSE_APP", strict=False, allowed=["CLOSE_APP", "UNKNOWN"]),
    ]

    # ── Wake-word / filler noise ──────────────────────────────────────────────
    cases += [
        TestCase("hey rehbar open chrome", "OPEN_APP"),
        TestCase("ok rehbar go to github", "OPEN_SITE"),
        TestCase("rehbar what time is it", "SYSTEM_INFO"),
        TestCase("actually set a timer for 5 minutes", "SET_ALARM"),
        TestCase("well search for java tutorial", "WEB_SEARCH"),
        TestCase("i mean close spotify", "CLOSE_APP"),
        TestCase("so make a new folder called test", "CREATE_FILE"),
        TestCase("rehbar rename this to notes", "RENAME_FILE"),
        TestCase("hey rehbar delete temp file", "DELETE_FILE"),
        TestCase("okay rehbar hello", "CHAT"),
    ]

    # ── Unsupported / OOD: should not become confident wrong commands ────────
    cases += [
        TestCase("translate this sentence to french", "UNKNOWN", strict=False, allowed=["UNKNOWN", "CHAT", "WEB_SEARCH"]),
        TestCase("summarize my email", "UNKNOWN", strict=False, allowed=["UNKNOWN", "CHAT"]),
        TestCase("book a flight to dubai", "UNKNOWN", strict=False, allowed=["UNKNOWN", "WEB_SEARCH"]),
        TestCase("send a message to ali", "UNKNOWN", strict=False, allowed=["UNKNOWN", "CHAT"]),
        TestCase("increase the volume", "UNKNOWN", strict=False, allowed=["UNKNOWN"]),
        TestCase("turn on bluetooth", "UNKNOWN", strict=False, allowed=["UNKNOWN"]),
        TestCase("take a screenshot", "UNKNOWN", strict=False, allowed=["UNKNOWN"]),
        TestCase("play some music", "UNKNOWN", strict=False, allowed=["UNKNOWN", "OPEN_APP"]),
        TestCase("pause the song", "UNKNOWN", strict=False, allowed=["UNKNOWN"]),
        TestCase("shut down the computer", "UNKNOWN", strict=False, allowed=["UNKNOWN"]),
    ]

    # ── Hard negatives against chat heuristic ─────────────────────────────────
    cases += [
        TestCase("what is the date", "SYSTEM_INFO"),
        TestCase("how much ram is used", "SYSTEM_INFO"),
        TestCase("what is machine learning", "WEB_SEARCH"),
        TestCase("who is the prime minister of india", "WEB_SEARCH"),
        TestCase("where is tokyo", "WEB_SEARCH"),
        TestCase("how to install python", "WEB_SEARCH"),
    ]

    # ── Massive templated expansion ────────────────────────────────────────────
    apps = ["chrome", "spotify", "notepad", "vscode", "calculator", "discord", "explorer"]
    sites = ["github", "youtube", "gmail", "reddit", "wikipedia", "linkedin", "google"]
    topics = ["python generators", "java streams", "today's weather", "cpu usage high", "best laptops", "space news"]
    files = ["report", "notes", "backup", "resume", "todo", "archive"]
    folders = ["projects", "photos", "documents", "logs", "configs", "assets"]

    for app in apps:
        cases += [
            TestCase(f"open {app}", "OPEN_APP"),
            TestCase(f"launch {app}", "OPEN_APP"),
            TestCase(f"start {app}", "OPEN_APP"),
            TestCase(f"close {app}", "CLOSE_APP"),
            TestCase(f"quit {app}", "CLOSE_APP"),
            TestCase(f"stop {app}", "CLOSE_APP"),
        ]

    for site in sites:
        cases += [
            TestCase(f"go to {site}", "OPEN_SITE"),
            TestCase(f"open {site}", "OPEN_SITE"),
            TestCase(f"visit {site}", "OPEN_SITE"),
        ]

    for topic in topics:
        cases += [
            TestCase(f"search for {topic}", "WEB_SEARCH"),
            TestCase(f"look up {topic}", "WEB_SEARCH"),
            TestCase(f"find information about {topic}", "WEB_SEARCH"),
        ]

    for name in files:
        cases += [
            TestCase(f"create file named {name}", "CREATE_FILE"),
            TestCase(f"delete file {name}", "DELETE_FILE"),
            TestCase(f"rename file {name} to final_{name}", "RENAME_FILE"),
        ]

    for name in folders:
        cases += [
            TestCase(f"create folder called {name}", "CREATE_FILE"),
            TestCase(f"remove folder {name}", "DELETE_FILE"),
            TestCase(f"rename folder {name} to old_{name}", "RENAME_FILE"),
        ]

    alarms = [
        "set alarm for 7 am",
        "set alarm for 8 30 am",
        "remind me in 20 minutes",
        "wake me in 2 hours",
        "timer for 15 minutes",
        "alarm for tomorrow at 6",
    ]
    for a in alarms:
        cases.append(TestCase(a, "SET_ALARM"))

    system_info_queries = [
        "what time is it",
        "tell me the date",
        "show battery level",
        "what is the cpu usage",
        "check disk space",
        "how much ram is used",
    ]
    for q in system_info_queries:
        cases.append(TestCase(q, "SYSTEM_INFO"))

    return cases


def normalize_prediction(pred: Any) -> Dict[str, Any]:
    if isinstance(pred, dict):
        return pred
    return {"intent": str(pred), "route": "unknown", "confidence": None, "top2": []}


def passed_case(case: TestCase, predicted_intent: str) -> bool:
    if case.strict:
        return predicted_intent == case.expected
    allowed = set(case.allowed or [case.expected])
    return predicted_intent in allowed


def print_section(title: str) -> None:
    print("\n" + "=" * 88)
    print(title)
    print("=" * 88)


def run_tests(classifier: IntentClassifier, cases: List[TestCase]) -> Dict[str, Any]:
    results = []
    total = len(cases)
    passed = 0

    by_expected = Counter()
    by_predicted = Counter()
    failures_by_expected = Counter()
    routes = Counter()

    for idx, case in enumerate(cases, start=1):
        raw = classifier.predict_debug(case.text)
        pred = normalize_prediction(raw)
        intent = pred.get("intent", "UNKNOWN")
        route = pred.get("route", "unknown")
        conf = pred.get("confidence", None)

        ok = passed_case(case, intent)
        if ok:
            passed += 1
        else:
            failures_by_expected[case.expected] += 1

        by_expected[case.expected] += 1
        by_predicted[intent] += 1
        routes[route] += 1

        row = {
            "id": idx,
            "text": case.text,
            "expected": case.expected,
            "strict": case.strict,
            "allowed": case.allowed or [],
            "predicted": intent,
            "route": route,
            "confidence": conf,
            "pass": ok,
            "notes": case.notes,
            "top2": pred.get("top2", []),
            "normalized": pred.get("normalized", case.text),
        }
        results.append(row)

    accuracy = passed / total if total else 0.0

    return {
        "summary": {
            "total": total,
            "passed": passed,
            "failed": total - passed,
            "accuracy": accuracy,
            "by_expected": dict(by_expected),
            "by_predicted": dict(by_predicted),
            "failures_by_expected": dict(failures_by_expected),
            "routes": dict(routes),
        },
        "results": results,
    }


def print_report(report: Dict[str, Any], show_failures: int = 50) -> None:
    summary = report["summary"]
    results = report["results"]

    print_section("OVERALL SUMMARY")
    print(f"Total tests : {summary['total']}")
    print(f"Passed      : {summary['passed']}")
    print(f"Failed      : {summary['failed']}")
    print(f"Accuracy    : {summary['accuracy']:.2%}")

    print_section("ROUTE USAGE")
    for route, count in sorted(summary["routes"].items(), key=lambda x: (-x[1], x[0])):
        print(f"{route:16} {count}")

    print_section("COUNTS BY EXPECTED LABEL")
    for label, count in sorted(summary["by_expected"].items()):
        print(f"{label:16} {count}")

    print_section("COUNTS BY PREDICTED LABEL")
    for label, count in sorted(summary["by_predicted"].items()):
        print(f"{label:16} {count}")

    print_section("FAILURES BY EXPECTED LABEL")
    if not summary["failures_by_expected"]:
        print("No failures.")
    else:
        for label, count in sorted(summary["failures_by_expected"].items(), key=lambda x: (-x[1], x[0])):
            print(f"{label:16} {count}")

    failed_rows = [r for r in results if not r["pass"]]
    print_section(f"FAILED CASES (showing up to {show_failures})")
    if not failed_rows:
        print("No failed cases.")
    else:
        for row in failed_rows[:show_failures]:
            print(f"[{row['id']:03}] text={row['text']!r}")
            print(f"      expected={row['expected']} predicted={row['predicted']} route={row['route']} conf={row['confidence']}")
            if row["allowed"]:
                print(f"      allowed={row['allowed']}")
            if row["top2"]:
                print(f"      top2={row['top2']}")


def export_csv(path: str, report: Dict[str, Any]) -> None:
    fields = [
        "id", "text", "expected", "strict", "allowed", "predicted",
        "route", "confidence", "pass", "notes", "normalized", "top2"
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in report["results"]:
            out = dict(row)
            out["allowed"] = json.dumps(out["allowed"], ensure_ascii=False)
            out["top2"] = json.dumps(out["top2"], ensure_ascii=False)
            writer.writerow(out)


def export_json(path: str, report: Dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=str, default="")
    parser.add_argument("--json", type=str, default="")
    parser.add_argument("--show-failures", type=int, default=50)
    parser.add_argument("--force-train", action="store_true")
    args = parser.parse_args()

    clf = IntentClassifier()
    clf.train(force=args.force_train, evaluate=False)

    cases = build_test_cases()
    report = run_tests(clf, cases)
    print_report(report, show_failures=args.show_failures)

    if args.csv:
        export_csv(args.csv, report)
        print(f"\nCSV written to: {args.csv}")
    if args.json:
        export_json(args.json, report)
        print(f"JSON written to: {args.json}")


if __name__ == "__main__":
    main()
