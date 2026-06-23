No problem! Two easy options for Windows 11 without Docker:

---

## Option 1 — Redis via WSL (Recommended)

WSL = Windows Subsystem for Linux
Runs Linux inside Windows 11 natively

---

**Step 1 — Check if WSL already installed:**
```
Open PowerShell as Administrator
Type:
wsl --version

If you see version number → WSL installed ✅
If error → need to install
```

**Step 2 — Install WSL if not installed:**
```
In PowerShell (Administrator):
wsl --install

→ Restarts your PC once
→ Ubuntu installs automatically
→ Set username and password when asked
```
---
WSL is installed successfully! Now you need to restart your PC first:

---

## Next Steps for Installing Redis via WSL:

---

**Step 1 — Restart your PC now:**
```
Save all your work
Restart Windows 11
```

---

**Step 2 — After restart, open Ubuntu:**
```
Click Start menu
Search "Ubuntu"
Click to open

First time opening:
→ Will ask you to set username
  (use simple name like: kuldeep)
→ Will ask you to set password
  (remember this password)
→ Wait 2-3 minutes for setup
```

---

**Step 3 — After Ubuntu opens, paste this:**
```bash
sudo apt-get update 
sudo apt-get install redis-server -y
```
```
→ Enter your password when asked
→ Wait for installation
→ Takes 2-3 minutes
```

---

**Step 4 — Start Redis:**
```bash
sudo service redis-server start
```

---

**Step 5 — Verify:**
```bash
redis-cli ping
```
```
Should return: PONG ✅
```

---

Go ahead and restart your PC — come back once Ubuntu opens and share what you see!

## Common Redis Commands:
```bash
sudo service redis-server start
redis-cli ping
```

```bash
redis-cli SET "zerodha:access_token" "TU5DdOLDmHfiKr9wKrRK6IeFlrTuFTl2"
redis-cli GET "zerodha:access_token"
redis-cli DEL "zerodha:access_token"  
redis-cli KEYS "zerodha:*"
redis-cli --scan --pattern "zerodha:*"
```

```bash
redis-cli DEL "zerodha:access_token"
redis-cli DEL "price:*"
redis-cli DEL "market:intelligence:*"
redis-cli DEL "market:maxpain:*"
redis-cli DEL "optionchain:*"
```

```bash
redis-cli KEYS "zerodha:*"
redis-cli KEYS "market:intelligence:*"
redis-cli KEYS "market:maxpain:*"
redis-cli KEYS "optionchain:*"
redis-cli KEYS "price:*"
```

kuldeep@kuldeep:~$ redis-cli GET zerodha:access_token
(nil)
kuldeep@kuldeep:~$ redis-cli SET zerodha:access_token TU5DdOLDmHfiKr9wKrRK6IeFlrTuFTl2
OK
kuldeep@kuldeep:~$ redis-cli GET zerodha:access_token
"TU5DdOLDmHfiKr9wKrRK6IeFlrTuFTl2"
kuldeep@kuldeep:~$ redis-cli DEL zerodha:access_token
(integer) 1
kuldeep@kuldeep:~$ redis-cli GET zerodha:access_token