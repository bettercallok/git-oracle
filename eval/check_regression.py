import json
import sys
import argparse

def main():
    parser = argparse.ArgumentParser(description="Check evaluation regression against a baseline")
    parser.add_argument("--min-score", type=float, default=0.75, help="Minimum acceptable evaluation score (0.0 to 1.0)")
    parser.add_argument("--report", type=str, default="eval_report.json", help="Path to the JSON eval report")
    parser.add_argument("--min-cause-score", type=float, default=0.5,
                        help="Minimum root-cause accuracy (0.0 to 1.0): the Investigator's top commit must be "
                             "the one that introduced the bug")
    
    args = parser.parse_args()

    try:
        with open(args.report, "r") as f:
            report = json.load(f)
    except FileNotFoundError:
        print(f"Error: Could not find report file {args.report}")
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Error: Invalid JSON in {args.report}")
        sys.exit(1)

    # Assuming the report contains a top-level "accuracy" float metric
    accuracy = report.get("accuracy")
    if accuracy is None:
        print(f"Error: Missing 'accuracy' metric in {args.report}")
        sys.exit(1)

    cause = report.get("cause_accuracy")
    print(f"Patch accuracy: target >= {args.min_score:.2f}, actual = {accuracy:.2f}")
    if cause is not None:
        print(f"Cause accuracy: target >= {args.min_cause_score:.2f}, actual = {cause:.2f}")

    failed = False
    if accuracy < args.min_score:
        print(f"FAILURE: patch accuracy ({accuracy:.2f}) is below baseline ({args.min_score:.2f})")
        failed = True
    if cause is not None and cause < args.min_cause_score:
        print(f"FAILURE: cause accuracy ({cause:.2f}) is below baseline ({args.min_cause_score:.2f})")
        failed = True
    if failed:
        sys.exit(1)

    print("SUCCESS: both accuracies meet their baselines.")
    sys.exit(0)

if __name__ == "__main__":
    main()
