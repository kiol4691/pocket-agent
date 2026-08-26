#!/bin/bash
# Open a URL ('intent' capability). Args: {"url": "..."}
URL=$(printf '%s' "$DSH_TOOL_ARGS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("url",""))')
dsh intent "view|$URL" && echo "opened: $URL"
