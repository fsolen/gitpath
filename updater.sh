#!/bin/bash

# -------- CONFIG --------
RELEASE_BUCKET="s3://configs/releases"
RELEASE_DIR="/config/releases"
CURRENT_SYMLINK="/config/current"
JITTER_MAX=300
# public key for signature verification
SIGN_KEY="/config/public_ed25519.pub"
# service to restart if config changes
SERVICE_NAME="myservice"
# retention
MAX_RELEASES=20
# ------------------------

# random jitter
sleep $((RANDOM % JITTER_MAX))

# download latest manifest and signature
curl -s -o /tmp/latest.json $RELEASE_BUCKET/latest.json
curl -s -o /tmp/latest.sig $RELEASE_BUCKET/latest.sig

# verify signature
ed25519-verify -p $SIGN_KEY -m /tmp/latest.json -s /tmp/latest.sig || exit 1

LATEST=$(jq -r .release_id /tmp/latest.json)
TREE_HASH=$(jq -r .tree_hash /tmp/latest.json)

# skip if already current
if [[ "$(readlink -f $CURRENT_SYMLINK)" == "$RELEASE_DIR/$LATEST" ]]; then
    exit 0
fi

# prepare new release dir using hardlink copy
mkdir -p $RELEASE_DIR/$LATEST
if [[ -d $CURRENT_SYMLINK ]]; then
    cp -al $CURRENT_SYMLINK/* $RELEASE_DIR/$LATEST/
fi

# sync full release
rsync -a --delete "$RELEASE_BUCKET/$LATEST/" "$RELEASE_DIR/$LATEST/"

# verify tree hash
COMPUTED_HASH=$(tar cf - "$RELEASE_DIR/$LATEST" | sha256sum | cut -d' ' -f1)
if [[ "$COMPUTED_HASH" != "$TREE_HASH" ]]; then
    echo "Tree hash mismatch! Aborting."
    exit 1
fi

# atomic switch
ln -sfn "$RELEASE_DIR/$LATEST" "$CURRENT_SYMLINK"

# restart service if needed
if systemctl is-active --quiet $SERVICE_NAME; then
    systemctl restart $SERVICE_NAME
fi

# cleanup old releases
cd $RELEASE_DIR
ls -1tr | head -n -$MAX_RELEASES | xargs -r rm -rf
