#!/vendor/bin/sh

V4A_DIR=/data/vendor/audiox
STATUS_FILE=$V4A_DIR/shm_status.bin
PARAMS_FILE=$V4A_DIR/shm_params.bin
BULK_FILE=$V4A_DIR/shm_bulk.bin

mkdir -p "$V4A_DIR/kernel"

create_file() {
    file="$1"
    size="$2"

    if [ ! -f "$file" ] || [ "$(stat -c %s "$file" 2>/dev/null)" != "$size" ]; then
        dd if=/dev/zero of="$file" bs="$size" count=1 2>/dev/null
    fi
}

create_file "$STATUS_FILE" 256
create_file "$PARAMS_FILE" 4096
create_file "$BULK_FILE" 4096

chown -R audioserver:audio "$V4A_DIR"
chmod 0777 "$V4A_DIR" "$V4A_DIR/kernel"
chmod 0666 "$STATUS_FILE" "$PARAMS_FILE" "$BULK_FILE"
/system/bin/restorecon -R "$V4A_DIR"
