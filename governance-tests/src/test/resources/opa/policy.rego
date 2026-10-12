package wanaku.tool_call

import rego.v1

default allow := false
allow if count(deny_reasons) == 0

decision := {"allow": true, "reason": null} if allow
decision := {"allow": false, "reason": min(deny_reasons)} if not allow

deny_reasons contains "unsupported_input_version" if input.version != "wanaku.opa.input/v1"
deny_reasons contains "unsupported_method" if input.method != "tools/call"
deny_reasons contains "tool_blocked" if input.tool_name in data.policy.blocked_tools[input.namespace]
deny_reasons contains "amount_not_a_number" if {
    data.policy.amount_limits[input.namespace][input.tool_name]
    not is_number(input.arguments.value.amount)
}
deny_reasons contains "amount_over_limit" if {
    limit := data.policy.amount_limits[input.namespace][input.tool_name]
    input.arguments.value.amount > limit
}
