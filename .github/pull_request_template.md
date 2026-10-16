<!--
Write the title and description in Chinese and English. Follow AGENTS.md.
Scale detail to the change; for unrelated areas or documentation-only changes, mark provider coverage as not applicable with a reason.
Do not include credentials or private server/user data. Keep local evidence in .test-artifacts/<task>/.
-->

## 改动与原因 / Change and rationale

<!-- Describe the concrete problem and resulting behavior; link the issue if applicable. -->

## 适用范围 / Applicability

<!-- Shared behavior or provider-specific protocol? Inspect current registration, including relevant in-development providers. Missing implementation is not unsupported. -->

| 库与入口 / Provider and entry point | 适用及接入状态 / Applicability and integration | 原因或缺口 / Reason or gap |
| --- | --- | --- |
| | | |

## 共享实现 / Shared implementation

<!-- Identify the reused/changed shared components or adapters and their consumers. Explain any necessary provider-specific branch. Protocol-only fixes do not require unrelated refactoring. -->

## 验证 / Validation

<!-- List checks actually run and providers/routes covered. Distinguish real-data checks on an authorized emulator dev installation from fixture, real-service, and changed-build tests; record version limitations. Follow AGENTS.md non-destructive and isolated-test boundaries. Shared behavior tests and integration checks serve different purposes. For visual changes include useful sanitized screenshots and relevant theme/tablet checks. -->

- [ ] 已按 `AGENTS.md` 的 **E-Ink Motion Policy** 在最终改动上运行 `verifyEInkMotion` 并记录结果；纯文档豁免须说明原因。 / Followed the E-Ink Motion Policy in `AGENTS.md`: ran `verifyEInkMotion` on the final changes and recorded the result, or explained the documentation-only exemption.

<!-- 对动效相关改动，记录 E-Ink 开启/关闭时的验证、涉及状态和入口，以及未验证项；静态检查通过不代表运行时无持续动画。 / For motion changes, record E-Ink enabled/disabled checks, affected states/routes, and unverified items; a passing static scan does not prove absence of ongoing animation. -->

<!-- Security-related work follows AGENTS.md Security Repair And Validation Workflow. Identify reused evidence and the scope of new checks; distinguish unverified specialized tests from passes. Do not conceal or work around platform review failures. -->

## 未完成或未验证 / Remaining gaps or unverified behavior

<!-- State missing integration, unavailable environments, and limitations explicitly; write none when there are none. -->
