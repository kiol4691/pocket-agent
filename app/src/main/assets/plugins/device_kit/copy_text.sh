#!/bin/bash
# Copy text to the phone clipboard ('clipboard' capability). Args: {"text": "..."}
TEXT=$(printf '%s' "$DSH_TOOL_ARGS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("text",""))')
dsh clipboard "$TEXT" && echo "copied ${#TEXT} chars"
