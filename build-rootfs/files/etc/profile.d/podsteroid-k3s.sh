#!/bin/sh
# PodSteroid — show K3s setup instructions on first login only.
FLAG_DIR=/etc/podsteroid
FLAG="$FLAG_DIR/.k3s-motd-shown"

if [ -f "$FLAG" ]; then
    return 0 2>/dev/null || true
fi

mkdir -p "$FLAG_DIR" 2>/dev/null || true
touch "$FLAG" 2>/dev/null || true

cat <<'MSG'

============================================================
 PodSteroid — Set up K3s agent
============================================================
To join this VM to a K3s cluster, run:

    setup-k3s-agent.sh <MASTER_URL> <K3S_TOKEN>

Example:
    setup-k3s-agent.sh https://XXX.XX.XXX.XXX:6443 K10xxxxxxxxxxxx...

The script installs the K3s agent, writes its config, creates an
OpenRC service, enables it at boot, and starts it:

    rc-service k3s-agent restart
    rc-update add k3s-agent default
MSG
