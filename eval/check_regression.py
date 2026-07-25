import json
import sys
import argparse

def main():
    parser = argparse.ArgumentParser(description="Check evaluation regression against a baseline")
    parser.add_argument("--min-score", type=float, default=0.75, help="Minimum acceptable evaluation score (0.0 to 1.0)")
    parser.add_argument("--report", type=str, default="eval_report.json", help="Path to the JSON eval report")
    
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

    print(f"Regression Check: Target >= {args.min_score:.2f}, Actual = {accuracy:.2f}")

    if accuracy < args.min_score:
        print(f"FAILURE: Model accuracy ({accuracy:.2f}) is below baseline ({args.min_score:.2f})")
        sys.exit(1)
    
    print("SUCCESS: Model accuracy meets or exceeds baseline.")
    sys.exit(0)

if __name__ == "__main__":
    main()
