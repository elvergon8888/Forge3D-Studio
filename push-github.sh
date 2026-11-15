#!/system/bin/sh
TOKEN="IYO3YB26Rp-p3g7pqtLYND7VL85ZcTIpVp5OoFSr-q4"
REPO="elvergon8888/Forge3D-Studio"
API="https://api.github.com/repos/$REPO"
LOCAL_DIR="."

# Obtener el commit actual
CURRENT_COMMIT=$(git rev-parse HEAD)
echo "Current commit: $CURRENT_COMMIT"

# Obtener el árbol actual del commit
CURRENT_TREE=$(git rev-parse HEAD^{tree})
echo "Current tree: $CURRENT_TREE"

# Obtener todos los archivos modificados
echo "Changed files:"
git diff --name-only HEAD~1 HEAD 2>/dev/null || git ls-files | head -20

# Para cada archivo modificado, subirlo como blob a GitHub
for file in $(git ls-files); do
    if [ -f "$file" ]; then
        CONTENT=$(base64 -w 0 "$file")
        # Subir el archivo como blob
        RESPONSE=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"content\":\"$CONTENT\"}" \
            "$API/git/blobs" 2>/dev/null)
        
        BLOB_SHA=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sha','ERROR'))" 2>/dev/null)
        echo "Uploaded $file -> blob $BLOB_SHA"
    fi
done

echo "Done uploading files"
