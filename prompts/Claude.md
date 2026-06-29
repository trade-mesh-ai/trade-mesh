Do NOT start asking Claude to write code.

Instead, use Claude only for these types of conversations:

✅ "Should this feature exist?"

✅ "Review this architecture."

✅ "Review this API."

✅ "Review this database design."

✅ "Is this overengineering?"

❌ Don't ask:
"Implement this feature."

For example, instead of saying:
I want to add AI prediction.

Ask:
Should AI prediction be part of TradeMesh at this stage? Review this proposal. If you reject it, explain why. Do not write code.

This keeps Claude acting as a Principal Engineer instead of a programmer.

❌ Don't ask
I want to add Portfolio Management.

Instead ask
Review this proposal. Should Portfolio Management be part of TradeMesh at this stage? If yes, propose the architecture. If no, explain why. Do not write code.

Another example
❌I want to use Redis here.
✅Review whether Redis is justified here. If another solution is better, recommend it. Do not write code.

Another
❌Let's add AI.
✅Review whether AI belongs in the current TradeMesh roadmap. Reject it if it introduces unnecessary complexity.

Here's the rule I would follow

When talking to Claude, never ask:
How do I implement this?

Always ask:
Should this be implemented?

# Switching from implementation to architecture review mode ensures that Claude provides high-level guidance and strategic insights rather than low-level coding instructions. This approach helps maintain the focus on design decisions, system architecture, and overall project direction.
Generate a concise PROJECT_CONTEXT.md that summarizes the current state of TradeMesh, including architecture, completed modules, pending work, architectural decisions, technology stack, and roadmap. Exclude implementation details and obsolete discussions.