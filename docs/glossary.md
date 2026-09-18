# 领域术语表

## Session（会话）

用户与一个选定模型之间的持久化对话聚合。会话包含按时间排序的用户消息、模型结果、工具消息、事件和 reasoning 区块。

## Model Request（模型请求）

一次向 provider 发起的实际流式 chat 请求。一次用户输入可能触发多个模型请求，例如模型请求工具后继续请求回答。

## Attempt（尝试）

同一个模型请求在网络或 provider 失败后的某一次重试。失败尝试不产生持久化 reasoning 区块。

## ReasoningBlock（思考区块）

一次成功 Model Request 返回的 reasoning 原文。它是会话时间线中的独立项，可流式更新，可在 UI 中展开或收起，但不会被回传给后续模型请求。

## Tool Round（工具轮次）

模型请求返回 tool call、工具执行并可能触发下一次模型请求的过程。每个成功 Model Request 仍单独拥有自己的 ReasoningBlock。

## Collapsed（收起）

思考区块的默认展示状态。生成中按原文换行显示最新四行，并将预览限制在约四个视觉行高内；生成结束只显示 Thinking 栏，不显示正文。

## Expanded（展开）

用户点击思考区块后进入的展示状态，显示该区块当前或最终保存的完整 reasoning 原文。每个区块独立维护此状态。

## Follow Latest（跟随最新内容）

会话列表已经位于底部时，流式 reasoning 或 assistant 内容更新会自动滚动到最新内容。用户向上查看历史后暂停跟随，回到底部后恢复。

## Successful Request（成功请求）

provider 流式响应正常结束，并成功得到 `ChatResult` 的模型请求。只有这种请求的 reasoning 才能持久化。
