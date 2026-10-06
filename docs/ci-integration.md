# CI Integration

ReviewAI can wait for a CI result before it reviews a Patch Set. This lets Jenkins, SonarQube, or another validation
system publish a Gerrit label vote first, so that the AI review has the outcome of the automated checks as context.

## Configure the review trigger

Configure the plugin to run its automatic review only after CI has applied a positive `Verified` vote:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified>=1
```

Add this setting to `gerrit.config` to apply it globally, or to a project's `project.config` in `refs/meta/config` to
apply it only to that project. ReviewAI evaluates the condition for each Patch Set. If CI has not voted yet, the review
is deferred; when the `Verified` vote arrives, ReviewAI starts the review automatically.

The CI service must be authorized to vote on the `Verified` label. A successful job should post `Verified+1`; a failed
build, test suite, or quality gate should post a negative vote such as `Verified-1`. For example, a CI job that uses the
Gerrit SSH command can publish its result with:

```bash
ssh -p 29418 gerrit.example.com gerrit review \
  --verified +1 --message 'Jenkins: build and tests passed' CHANGE_NUMBER,PATCHSET_NUMBER
```

Use the equivalent Gerrit review step provided by your CI integration if available.

## Jenkins and SonarQube example

In Jenkins, trigger a job for each Gerrit Patch Set, run the build and tests, then run SonarQube analysis. Publish
`Verified+1` only when all required checks succeed. If the SonarQube quality gate reports a code-quality violation,
publish `Verified-1` and do not mark the Patch Set verified.

The relevant stages of a declarative pipeline might look like this:

```groovy
stage('Build and test') {
    steps {
        sh 'mvn test'
    }
}
stage('SonarQube') {
    steps {
        withSonarQubeEnv('SonarQube') {
            sh 'mvn sonar:sonar'
        }
        waitForQualityGate abortPipeline: true
    }
}
stage('Vote') {
    steps {
        sh '''ssh -p 29418 gerrit.example.com gerrit review \\
      --verified +1 --message 'Jenkins: tests and SonarQube quality gate passed' \\
      $GERRIT_CHANGE_NUMBER,$GERRIT_PATCHSET_NUMBER'''
    }
}
```

Add failure handling that posts `Verified-1` with the build or quality-gate result. The exact Jenkins environment
variable names and voting step depend on the Gerrit trigger/integration in use.

## How the `Verified` description affects AI findings

ReviewAI includes the current value, possible values, and configured description of labels referenced by
`aiReviewApplicableIf` in its review context. The description communicates what a successful CI vote proves; the
label name alone does not.

ReviewAI derives what a passing vote covers from the wording of that description and states it in the prompt as a
`Covers` entry, for example:

```
Verified:
  Current value: +1
  Possible values: +0, +1
  Description: Verified label usually means that automated tests have run and the code compiles and passes basic checks
  Covers: compilation, automated tests
```

A covered scope makes the label conclusive for that scope: ReviewAI does not report a finding that rests on missing or
inconclusive evidence — a search that returned no match, a declaration it could not see, an import it could not verify
— even when its own inspection appears to contradict the label. Direct evidence in the patch itself still counts: a
syntax error in the added lines, a removed declaration, or an incompatible signature may be reported regardless of the
label, and unrelated scopes are unaffected. A label whose votes conflict (for example a human `-1` against a CI `+1`)
covers nothing, so a stale CI vote cannot suppress a finding once someone has voted the label down.

When `Verified` has no configured description, ReviewAI uses this default meaning:

> Verified label usually means that automated tests have run and the code compiles and passes basic checks

The wording matters: it is matched against the review scopes ReviewAI knows (compilation, automated tests, static
analysis, security scanning, documentation). A description that names none of them leaves the label non-conclusive, and
ReviewAI keeps reporting issues there as before.

If your Jenkins and SonarQube pipeline validates the code more comprehensively, configure a more assertive description
on the Gerrit `Verified` label, for example in `project.config`:

```ini
[label "Verified"]
    description = The code is correct: automated tests have run successfully, and the code compiles and passes all checks.
```

With that stronger statement, ReviewAI covers compilation, automated tests, static analysis, and documentation for the
listed scopes. Use it only when the positive vote genuinely covers all the checks claimed by the description;
otherwise, retain a narrower description so that ReviewAI can continue to flag evidence-backed issues outside CI's
coverage.
