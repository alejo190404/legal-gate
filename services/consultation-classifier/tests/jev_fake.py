from types import SimpleNamespace


class FakeJev:
    """Stands in for TypeSafeClient: answers every question from a fixed table and records the calls."""

    def __init__(self, answers: dict[str, dict] | Exception) -> None:
        self.answers = answers
        self.calls: list[dict] = []

    def system_one(self, state: object, questions: dict) -> SimpleNamespace:
        self.calls.append({"state": state, "questions": questions})
        if isinstance(self.answers, Exception):
            raise self.answers
        return SimpleNamespace(
            answers={name: SimpleNamespace(**self.answers[name]) for name in questions if name in self.answers}
        )


def jev_verdict(verdict: str, confidence: float = 0.9) -> FakeJev:
    return FakeJev({"verdict": {"choice": verdict, "confidence": confidence}})
