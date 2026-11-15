#!/system/bin/sh
TOKEN="IYO3YB26Rp-p3g7pqtLYND7VL85ZcTIpVp5OoFSr-q4"
REPO="elvergon8888/Forge3D-Studio"
API="https://api.github.com/repos/$REPO"
LOCAL_DIR="."

# Crear el repositorio si no existe
CREATE_RESPONSE=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Forge3D-Studio","description":"Forge3D Studio - Editor 3D Android con MCP","private":false}' \
  "https://api.github.com/user/repos" 2>/dev/null)
echo "Create repo response: $CREATE_RESPONSE" | head -c 200
echo ""

# Obtener el commit actual
CURRENT_COMMIT=$(git rev-parse HEAD)
echo "Current commit: $CURRENT_COMMIT"

# Obtener el árbol actual del commit
CURRENT_TREE=$(git rev-parse HEAD^{tree})
echo "Current tree: $CURRENT_TREE"

# Obtener el ref actual de main
CURRENT_REF=$(curl -s -H "Authorization: Bearer $TOKEN" "$API/git/ref/heads/master" 2>/dev/null)
echo "Current ref: $CURRENT_REF" | head -c 200
echo ""

# Verificar si el ref existe
CURRENT_SHA=$(echo "$CURRENT_REF" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['object']['sha'])" 2>/dev/null)
echo "Current SHA: $CURRENT_SHA"

# Si no hay ref, crear uno nuevo apuntando al commit actual
if [ -z "$CURRENT_SHA" ] || [ "$CURRENT_SHA" = "None" ] || [ "$CURRENT_SHA" = "ERROR" ]; then
    echo "Creating new ref main -> $CURRENT_COMMIT"
    curl -s -X POST -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"ref\":\"refs/heads/master\",\"sha\":\"$CURRENT_COMMIT\"}" \
      "$API/git/refs" 2>/dev/null
    echo ""
    echo "Push completed!"
else
    echo "Repository already has ref: $CURRENT_SHA"
fi
