# nginx reverse proxy — api.crixaa.com

TLS-terminating reverse proxy for the backend Docker container (published on
`127.0.0.1:9092`). Handles HTTP REST, the WebSocket STOMP endpoint (`/ws`), and
SSE streaming for AI generation (`/api/templates/<id>/ai-generate`).

Config: [`api.crixaa.com.conf`](api.crixaa.com.conf) — **HTTP-only**. TLS is added
by `certbot --nginx`, which clones the port-80 server block into a 443 block,
fills in the cert paths, and inserts the HTTP→HTTPS redirect. Don't hand-add
`ssl_*` directives.

## Prerequisites

- **DNS:** `A` record `api.crixaa.com` → EC2 public IP. Verify: `dig +short api.crixaa.com`
- **Security group:** inbound **80** and **443** open; **9092 stays closed** (nginx reaches it over loopback).
- **Backend up:** `curl -sf http://127.0.0.1:9092/actuator/health` returns `{"status":"UP"}`.
- App runs with `server.forward-headers-strategy: framework` (set in `application.yml`) so it honors the `X-Forwarded-*` headers nginx sends.

## Setup

```bash
# 1. Install
sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx

# 2. Install this config (HTTP-only)
sudo cp deploy/nginx/api.crixaa.com.conf /etc/nginx/sites-available/api.crixaa.com
sudo ln -s /etc/nginx/sites-available/api.crixaa.com /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default   # optional

# 3. Test + reload
sudo nginx -t && sudo systemctl reload nginx

# 4. Obtain + install the cert (adds the 443 block + redirect in place)
sudo certbot --nginx -d api.crixaa.com
```

certbot installs a systemd timer for auto-renewal and reloads nginx itself after
renewals (nginx authenticator), so no extra hooks are needed.

## Verify

```bash
# HTTPS up + HTTP redirects to HTTPS
curl -sI https://api.crixaa.com/ | head -n1
curl -sI http://api.crixaa.com/ | grep -i location

# Health (ACL allows loopback/VPC only — connect via 127.0.0.1 but keep the real Host/SNI)
curl --resolve api.crixaa.com:443:127.0.0.1 https://api.crixaa.com/actuator/health

# Rest of actuator is blocked from outside (expect 403)
curl -so /dev/null -w '%{http_code}\n' https://api.crixaa.com/actuator/env
```

## Notes

- **HTTP/2:** certbot generates `listen 443 ssl;` without HTTP/2. To enable it,
  edit the generated 443 block and add `http2 on;` (nginx ≥ 1.25.1) or change the
  listen line to `listen 443 ssl http2;` (older nginx), then `nginx -t && reload`.
- **Body size:** nginx allows `50m`, but Spring caps multipart at `3MB`
  (`spring.servlet.multipart.max-file-size`). Raise the Spring limit too if you
  accept larger uploads.
- **OCSP stapling** isn't added by certbot. If you want it, add `ssl_stapling on;`,
  `ssl_stapling_verify on;`, `ssl_trusted_certificate .../chain.pem;` and a
  `resolver` to the generated 443 block.
