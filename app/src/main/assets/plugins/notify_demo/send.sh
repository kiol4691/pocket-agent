#!/bin/bash
# Type-A plugin script: args arrive as JSON in $DSH_TOOL_ARGS, results go to stdout.
TITLE=$(printf '%s' "$DSH_TOOL_ARGS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("title",""))')
BODY=$(printf '%s' "$DSH_TOOL_ARGS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("body",""))')
if dsh notify "$TITLE" "$BODY"; then
  echo "notification sent: $TITLE"
else
  echo "failed to send notification (is the 'notify' capability granted?)"
  exit 1
fi
