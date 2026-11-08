#!/usr/bin/env python3
from dataclasses import dataclass
from statistics import mean, median


@dataclass
class Summary:
    """Basic statistics for a list of numbers."""
    mean: float
    median: float
    maximum: float = 0.0


def summarize(values: list[float]) -> Summary:
    if not values:
        raise ValueError(f"empty input: {values!r}")
    return Summary(mean(values), median(values), max(values))


if __name__ == "__main__":
    print(summarize([3, 1, 4, 1, 5, 9, 2, 6]), None, True)
