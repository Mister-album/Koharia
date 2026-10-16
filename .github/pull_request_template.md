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

- [ ] 已按 `AGENTS.md` 的 **Motion Design And E-Ink Compatibility** 运行适用的 `verifyEInkMotion` 并记录结果，或说明不适用原因。 / Followed Motion Design And E-Ink Compatibility in `AGENTS.md`: ran the applicable `verifyEInkMotion` check and recorded its result, or explained why it does not apply.

<!-- 对动效改动，分别记录普通模式的预期动画/反馈与 E-Ink 模式的适配验证，范围与改动相称。不得以全局取消动画代替适配；列出涉及状态、入口和未验证项。静态检查不能证明任一模式的运行时表现。 / For motion changes, record intended ordinary-mode animation/feedback and E-Ink adaptation checks, proportional to the change. Globally removing animation is not adaptation. List affected states/routes and unverified items; static checks do not prove either mode's runtime behavior. -->

<!-- Security-related work follows AGENTS.md Security Repair And Validation Workflow. Identify reused evidence and the scope of new checks; distinguish unverified specialized tests from passes. Do not conceal or work around platform review failures. -->

## 未完成或未验证 / Remaining gaps or unverified behavior

<!-- State missing integration, unavailable environments, and limitations explicitly; write none when there are none. -->
