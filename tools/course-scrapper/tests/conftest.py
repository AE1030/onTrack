"""The script's file name has a hyphen, so it cannot be imported by name. Load it once here."""

import importlib.util
import sys
from pathlib import Path

import pytest

_PATH = Path(__file__).resolve().parent.parent / "course-scrapper.py"


@pytest.fixture(scope="session")
def cs():
    spec = importlib.util.spec_from_file_location("course_scrapper", _PATH)
    module = importlib.util.module_from_spec(spec)
    # Dataclasses resolve their annotations through sys.modules, so register before running.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module
