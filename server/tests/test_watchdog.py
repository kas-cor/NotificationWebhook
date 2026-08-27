import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))


def test_include_rule_wins_over_skip():
    from watchdog import is_important

    important, reason = is_important(
        {"title": "test deployment failed", "text": ""},
        {
            "always_include": {"patterns": ["deployment"]},
            "always_skip": {"patterns": ["test"]},
            "default_importance": "low",
            "medium_apps": [],
        },
    )

    assert important is True
    assert reason == "include-pattern: 'deployment'"


def test_state_defaults_are_not_shared(monkeypatch, tmp_path):
    import watchdog

    monkeypatch.setattr(watchdog, "STATE_FILE", tmp_path / "state.json")
    first = watchdog.load_state()
    first["importance_rules"]["always_include"]["patterns"].append("custom")
    second = watchdog.load_state()

    assert "custom" not in second["importance_rules"]["always_include"]["patterns"]
