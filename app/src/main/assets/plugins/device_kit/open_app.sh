#!/bin/bash
# Launch an app by package ('intent' capability). Args: {"package": "..."}
PKG=$(printf '%s' "$DSH_TOOL_ARGS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("package",""))')
dsh intent "app|$PKG" && echo "launched: $PKG"
