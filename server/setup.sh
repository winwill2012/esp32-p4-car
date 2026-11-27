#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077

usage() {
    printf '用法：bash setup.sh <固定公网 IPv4> [TURN 本机 IPv4]\n' >&2
    exit 2
}

valid_ipv4() {
    local value=$1 octet
    [[ $value =~ ^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}$ ]] || return 1
    local -a octets
    IFS=. read -r -a octets <<< "$value"
    for octet in "${octets[@]}"; do
        (( octet <= 255 )) || return 1
    done
}

public_ipv4() {
    valid_ipv4 "$1" || return 1
    local a b c d
    IFS=. read -r a b c d <<< "$1"
    (( a > 0 && a < 224 && a != 10 && a != 127 &&
        !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254) &&
        !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168) &&
        !(a == 192 && b == 0 && (c == 0 || c == 2)) && !(a == 198 && (b == 18 || b == 19)) &&
        !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113) ))
}

read_setting() {
    sed -n "s/^$1=//p" .env
}

random_key() {
    od -An -v -N32 -tx1 /dev/urandom | tr -d ' \n'
}

[[ $# -ge 1 && $# -le 2 ]] || usage
public_ip=$1
public_ipv4 "$public_ip" || { printf '请输入可公网访问的 IPv4。\n' >&2; exit 2; }
docker compose version >/dev/null || { printf '请先安装 Docker Engine 和 Docker Compose 插件。\n' >&2; exit 1; }
if command -v flock >/dev/null; then
    exec 9>.setup.lock
    flock 9
fi

if [[ -f .env ]]; then
    if [[ $(read_setting PUBLIC_IP) != "$public_ip" ]]; then
        printf '已有部署的 IP 与输入值不同。请先按 README 的迁移步骤更新现有配置。\n' >&2
        exit 2
    fi
    turn_local_ip=${2:-$(read_setting TURN_LOCAL_IP)}
    device_key=$(read_setting DEVICE_KEY)
    turn_secret=$(read_setting TURN_SECRET)
    cars=$(read_setting P4_CARS)
else
    turn_local_ip=${2:-}
    if [[ -z $turn_local_ip ]] && command -v ip >/dev/null; then
        turn_local_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<NF;i++) if ($i=="src") {print $(i+1); exit}}') || true
    fi
    device_key=$(random_key)
    turn_secret=$(random_key)
fi

valid_ipv4 "$turn_local_ip" || { printf '无法确定服务器网卡地址，请把本机 IPv4 作为第二个参数。\n' >&2; exit 2; }
for key in "$device_key" "$turn_secret"; do
    [[ $key =~ ^[0-9a-f]{64}$ ]] || { printf '.env 中的密钥格式有误。\n' >&2; exit 2; }
done
unset key

env_tmp=$(mktemp .env.XXXXXX)
trap 'rm -f -- "$env_tmp"' EXIT
printf 'PUBLIC_IP=%s\nTURN_LOCAL_IP=%s\nDEVICE_KEY=%s\nTURN_SECRET=%s\n' \
    "$public_ip" "$turn_local_ip" "$device_key" "$turn_secret" > "$env_tmp"
if [[ -n ${cars:-} ]]; then
    printf 'P4_CARS=%s\n' "$cars" >> "$env_tmp"
fi
mv -f -- "$env_tmp" .env

mkdir -p -m 700 setup-output
chmod 700 setup-output
printf '#pragma once\n#define INTERCOM_HOST "%s"\n#define DEVICE_KEY "%s"\n' \
    "$public_ip" "$device_key" > setup-output/config.local.h
printf 'https://%s/p4-intercom/rtc/\n' "$public_ip" > setup-output/android-link.txt

docker compose up -d --build
printf '\n固件配置：%s/setup-output/config.local.h\n' "$PWD"
printf 'Android 连接地址：%s/setup-output/android-link.txt\n' "$PWD"
printf '服务地址：https://%s\n证书签发进度：docker compose logs -f https\n' "$public_ip"
