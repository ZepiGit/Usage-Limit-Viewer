#!/usr/bin/env python3
"""Give explicitly named XCTest screenshots stable filenames; reject missing captures."""

import argparse
import json
from pathlib import Path
import shutil


def attachment_records(value):
    if isinstance(value, dict):
        if "exportedFileName" in value:
            yield value
        for nested in value.values():
            yield from attachment_records(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from attachment_records(nested)


def export(source: Path, destination: Path, names: list[str]) -> None:
    source = source.resolve()
    records = []
    for manifest in source.rglob("*.json"):
        records.extend(attachment_records(json.loads(manifest.read_text())))
    destination.mkdir(parents=True, exist_ok=True)
    for name in names:
        matches = []
        for record in records:
            label = record.get("suggestedHumanReadableName", record.get("name", ""))
            path = (source / record["exportedFileName"]).resolve()
            if not path.is_relative_to(source):
                raise ValueError("Attachment path leaves its export directory")
            if label.startswith(name) and path.suffix.lower() == ".png" and path.is_file():
                matches.append(path)
        if len(matches) != 1:
            raise ValueError(f"Expected one {name} screenshot, found {len(matches)}")
        output = destination / f"{name}.png"
        shutil.copyfile(matches[0], output)
        print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("names", nargs="+")
    args = parser.parse_args()
    export(args.source, args.destination, args.names)
