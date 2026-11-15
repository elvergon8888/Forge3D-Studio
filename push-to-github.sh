#!/system/bin/sh
TOKEN="IYO3YB26Rp-p3g7pqtLYND7VL85ZcTIpVp5OoFSr-q4"
REPO="elvergon8888/Forge3D-Studio"
API="https://api.github.com/repos/$REPO"

# Obtener el commit actual
CURRENT_COMMIT=$(git rev-parse HEAD)
echo "Current commit: $CURRENT_COMMIT"

# Crear el blob del árbol completo (simplificado - solo subimos archivos clave)
# En realidad, para un push completo necesitamos el objeto tree completo.
# Como es complejo, vamos a intentar usar git push con el helper de http

# Intentar usar git-remote-https si está disponible
if [ -x "$HOME/bin/git-remote-https" ]; then
    export PATH="$HOME/bin:$PATH"
    git push origin master 2>&1 | head -20
else
    echo "No hay git-remote-https disponible"
    # Como alternativa, subir archivos individuales a través de la API
    echo "Intentando subir archivos individualmente..."
    
    # Obtener el árbol actual
    TREE=$(git rev-parse HEAD^{tree})
    echo "Tree: $TREE"
    
    # Crear un nuevo commit en GitHub usando la API
    # Primero obtenemos el rama actual
    REF=$(curl -s -H "Authorization: Bearer $TOKEN" "$API/git/ref/heads/master" 2>/dev/null)
    echo "Current ref: $REF"
    
    # Extraer el SHA actual
    CURRENT_SHA=$(echo "$REF" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['object']['sha'])" 2>/dev/null)
    echo "Current SHA: $CURRENT_SHA"
    
    # Obtener el árbol actual
    TREE_SHA=$(curl -s -H "Authorization: Bearer $TOKEN" "$API/git/trees/$CURRENT_SHA" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['tree'][0]['sha'] if d.get('tree') else 'none')" 2>/dev/null)
    echo "Tree SHA: $TREE_SHA"
fi
