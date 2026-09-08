"""One version source for native packages, APKs and release metadata."""
import os

VERSION = "0.2.1"


def build_number():
    number = int(os.environ.get("ASTER_BUILD_NUMBER", "3"))
    if not 3 <= number <= 65535:
        raise ValueError("ASTER_BUILD_NUMBER must be between 3 and 65535")
    return number


def display_version():
    return f"{VERSION}-preview.{build_number()}"


def native_version():
    return f"{VERSION}.{build_number()}"
