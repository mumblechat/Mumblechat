# Cloudflare Setup for MumbleChat Relay Nodes

## Current Setup Issue

**Problem:** Relay nodes use custom ports that Cloudflare can't proxy:
- TCP Port: 19371
- WebSocket Port: 19372
- Dashboard Port: 19380

**Cloudflare Limitations:**
- ❌ Can't proxy custom TCP ports (only 80/443 for HTTP/HTTPS)
- ❌ Orange cloud (proxied) won't work for relay traffic
- ✅ Can use DNS-only (gray cloud) mode
- ✅ Can use Cloudflare Tunnel for specific ports

## Solution Options

### Option 1: DNS-Only Mode (Recommended for Relay Nodes)

**Setup:**
1. Go to Cloudflare Dashboard → DNS → Records
2. Add A record: `relay.mumblechat.com` → `160.187.80.116`
3. Click the orange cloud to turn it **GRAY** (DNS only)
4. Add SRV records for service discovery

**DNS Configuration:**
```
Type: A
Name: relay
Content: 160.187.80.116
Proxy: OFF (gray cloud)
TTL: Auto

Type: SRV
Name: _relay._tcp.mumblechat.com
Priority: 10
Weight: 100
Port: 19371
Target: relay.mumblechat.com

Type: SRV
Name: _relay-ws._tcp.mumblechat.com
Priority: 10
Weight: 100
Port: 19372
Target: relay.mumblechat.com
```

**Result:**
- Nodes connect directly to: `relay.mumblechat.com:19371`
- WebSocket connects to: `relay.mumblechat.com:19372`
- No Cloudflare proxy interference

---

### Option 2: Cloudflare Tunnel (For Dashboard Only)

**Use Case:** Expose the dashboard (port 19380) securely via HTTPS

**Setup:**
```bash
# Install cloudflared
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o cloudflared
chmod +x cloudflared
sudo mv cloudflared /usr/local/bin/

# Login and create tunnel
cloudflared tunnel login
cloudflared tunnel create mumblechat-relay

# Configure tunnel
cat > ~/.cloudflared/config.yml << EOF
tunnel: <TUNNEL-ID>
credentials-file: /root/.cloudflared/<TUNNEL-ID>.json

ingress:
  - hostname: dashboard.mumblechat.com
    service: http://localhost:19380
  - service: http_status:404
EOF

# Route traffic
cloudflared tunnel route dns mumblechat-relay dashboard.mumblechat.com

# Run tunnel
cloudflared tunnel run mumblechat-relay
```

**Result:**
- Dashboard accessible at: `https://dashboard.mumblechat.com`
- SSL/TLS handled by Cloudflare
- Relay ports (19371/19372) remain direct connection

---

### Option 3: Hybrid Setup (Best Approach)

**Configuration:**
1. **Website/API**: Proxied through Cloudflare (orange cloud)
   - `mumblechat.com` → 160.187.80.116:80
   - `api.mumblechat.com` → 160.187.80.116:443

2. **Relay Endpoints**: DNS-only (gray cloud)
   - `relay.mumblechat.com` → 160.187.80.116:19371
   - Direct connection, no proxy

3. **Dashboard**: Cloudflare Tunnel (optional)
   - `dashboard.mumblechat.com` → tunnel → localhost:19380
   - Secure HTTPS access

---

## Firewall Rules (Required)

Since relay uses gray cloud (direct IP exposure), configure firewall:

```bash
# Allow relay ports
sudo ufw allow 19371/tcp comment "MumbleChat Relay TCP"
sudo ufw allow 19372/tcp comment "MumbleChat Relay WebSocket"
sudo ufw allow 19380/tcp comment "MumbleChat Dashboard"

# Optional: Restrict dashboard to specific IPs
sudo ufw deny 19380/tcp
sudo ufw allow from YOUR_HOME_IP to any port 19380

# Check status
sudo ufw status numbered
```

---

## Cloudflare Settings for mumblechat.com

### Page Rules (if using orange cloud for website):
```
Rule 1: relay.mumblechat.com/*
- Cache Level: Bypass
- Security Level: Essentially Off
- Disable Performance

Rule 2: dashboard.mumblechat.com/*
- Cache Level: Bypass
- Always Use HTTPS: On
```

### Firewall Rules:
```
Expression: (http.host eq "relay.mumblechat.com")
Action: Allow
```

### SSL/TLS Mode:
- For website (orange cloud): **Full (strict)**
- For relay (gray cloud): **Not applicable** (direct connection)

---

## Current Setup (mumblechat.com)

### Existing DNS Records:
```
mumblechat.com          A    160.187.80.116  (Proxied - Orange)
www.mumblechat.com      A    160.187.80.116  (Proxied - Orange)
```

### Add These Records:
```
relay.mumblechat.com    A    160.187.80.116  (DNS only - Gray)
relay1.mumblechat.com   A    160.187.80.116  (DNS only - Gray)
relay2.mumblechat.com   A    <OTHER-SERVER>  (DNS only - Gray)
```

---

## Multi-Node Discovery System

### Option A: DHT Bootstrap Nodes
Users' nodes connect to well-known bootstrap nodes to join DHT network:

**Cloudflare Configuration:**
```
bootstrap1.mumblechat.com  →  160.187.80.116:19371  (Gray cloud)
bootstrap2.mumblechat.com  →  ANOTHER-IP:19371      (Gray cloud)
bootstrap3.mumblechat.com  →  THIRD-IP:19371        (Gray cloud)
```

**In Desktop Relay Config:**
```json
{
  "p2p": {
    "bootstrapNodes": [
      "tcp://bootstrap1.mumblechat.com:19371",
      "tcp://bootstrap2.mumblechat.com:19371",
      "tcp://bootstrap3.mumblechat.com:19371"
    ]
  }
}
```

### Option B: Centralized Registry API
Create a simple API endpoint for node discovery:

**Setup:**
```
api.mumblechat.com/v1/nodes          → List all active nodes
api.mumblechat.com/v1/nodes/:nodeId  → Get specific node endpoints
```

**Cloudflare Configuration:**
- Keep `api.mumblechat.com` proxied (orange cloud)
- Cache API responses for 60 seconds
- Rate limit: 100 requests/minute per IP

---

## Security Considerations

### DDoS Protection:
1. **Gray Cloud Relay**: Direct IP exposure
   - Use Cloudflare Spectrum (paid) to proxy TCP/UDP
   - Or use fail2ban + rate limiting at server level

2. **Orange Cloud Website**: Cloudflare handles DDoS
   - Automatic protection included
   - Enable "Under Attack Mode" if needed

### IP Filtering:
```nginx
# In nginx config for dashboard
location /api/ {
    # Only allow from Cloudflare IPs or specific IPs
    allow 103.21.244.0/22;  # Cloudflare range
    allow YOUR_HOME_IP;
    deny all;
}
```

---

## Implementation Steps

### 1. Add DNS Records in Cloudflare:
```
1. Login to Cloudflare → Select mumblechat.com
2. Go to DNS → Records
3. Add:
   - relay.mumblechat.com → 160.187.80.116 (Gray cloud)
   - relay1.mumblechat.com → 160.187.80.116 (Gray cloud)
4. Save
```

### 2. Update Relay Node Config:
```json
{
  "relay": {
    "port": 19371,
    "host": "0.0.0.0",
    "publicEndpoint": "tcp://relay.mumblechat.com:19371",
    "advertiseEndpoint": true
  }
}
```

### 3. Update Website Discovery:
```javascript
// In relay-node.html
const BOOTSTRAP_NODES = [
  'tcp://relay.mumblechat.com:19371',
  'tcp://relay1.mumblechat.com:19371'
];

// Or fetch from API
const nodes = await fetch('https://api.mumblechat.com/v1/nodes');
```

### 4. Test Connection:
```bash
# Test DNS resolution
dig relay.mumblechat.com

# Test TCP connection
nc -zv relay.mumblechat.com 19371

# Test WebSocket
wscat -c ws://relay.mumblechat.com:19372
```

---

## Monitoring Dashboard

Create a status page at: `status.mumblechat.com`

Shows:
- ✅ Website: mumblechat.com (online/offline)
- ✅ Relay 1: relay.mumblechat.com:19371 (online/offline)
- ✅ Relay 2: relay1.mumblechat.com:19371 (online/offline)
- ✅ Total Active Nodes: 42
- ✅ Network Health: 99.8%

Use Cloudflare Workers to check endpoint health every minute.

---

## Cost Considerations

### Free Tier (Current):
- ✅ DNS management
- ✅ Orange cloud proxy (HTTP/HTTPS)
- ✅ Basic DDoS protection
- ❌ Cloudflare Spectrum (TCP/UDP proxy)
- ❌ Load balancing

### Paid Features (If Needed):
- **Cloudflare Spectrum** ($5/month): Proxy TCP port 19371
- **Load Balancing** ($5/month): Auto-route to healthy nodes
- **Cloudflare Workers** (Free tier sufficient): Node discovery API

---

## Recommended Setup for Production

```
┌─────────────────────────────────────────┐
│         mumblechat.com (Orange)         │
│    Website + API + Admin Dashboard      │
│         Cloudflare Proxied              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      relay.mumblechat.com (Gray)        │
│     Primary Bootstrap Node (19371)      │
│         Direct Connection               │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         User Relay Nodes (DHT)          │
│    Discovered via bootstrap node        │
│    Connect peer-to-peer directly        │
└─────────────────────────────────────────┘
```

**Benefits:**
- Website gets Cloudflare DDoS protection
- Relay nodes connect directly (low latency)
- Bootstrap node helps new nodes join network
- Users run relay on their own IPs (decentralized)
