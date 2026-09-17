import re, datetime, statistics, os
OUT="/home/wnt/gta6/build/captures/ple343-verify"
TZ=datetime.timezone(datetime.timedelta(hours=3)); YEAR=2026
phases=[]
for line in open(f"{OUT}/phases.txt"):
    k,t,ts=line.split(); phases.append((k,t,float(ts)))
spans={}
for i,(k,tag,ts) in enumerate(phases):
    if k=="PHASE_BEGIN":
        e=next((t for kk,g,t in phases[i+1:] if kk=="PHASE_END" and g==tag),None)
        if e: spans[tag]=(ts,e)
def stamp(s):
    return datetime.datetime.strptime(f"{YEAR}-{s}","%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=TZ).timestamp()
FS=re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Feedback stats: window (\d+) ms video received (\d+)")
JIT=re.compile(r"packet_jitter_ms (\d+\.\d+)")
PRB=re.compile(r"probe_rtt_ms (\d+\.\d+)")
TAK=re.compile(r"per_s takion (\d+\.\d+)")
rows=[]
for line in open(f"{OUT}/session_logcat.txt",errors="replace"):
    m=FS.search(line)
    if not m: continue
    j=JIT.search(line); p=PRB.search(line); tk=TAK.search(line)
    rows.append(dict(t=stamp(m.group(1)),win=int(m.group(2)),frames=int(m.group(3)),
        jit=float(j.group(1)) if j else None, probe=float(p.group(1)) if p else None,
        pkts=float(tk.group(1)) if tk else None))
pings=[]
for line in open(f"{OUT}/phone_ping.txt",errors="replace"):
    m=re.match(r"\[(\d+\.\d+)\].*time=(\d+(?:\.\d+)?) ms",line)
    if m: pings.append((float(m.group(1)),float(m.group(2))))
def pct(v,q):
    s=sorted(v); return s[min(len(s)-1,int(len(s)*q))]
print(f"{len(rows)} stats lines  {len(pings)} pings")
print("| phase | n | ping med | ping p10-p90 | ping IPDV mean|d| | probe med | pkt_jitter_ms | pkts/s | frames/s | pkts/frame |")
print("|"+"---|"*10)
for tag,(a,b) in spans.items():
    r=[x for x in rows if a+10<=x["t"]<=b]
    p=[v for t,v in pings if a+10<=t<=b]
    if not r or len(p)<5: continue
    ipdv=statistics.mean(abs(p[i]-p[i-1]) for i in range(1,len(p)))
    fps=statistics.median([x["frames"]*1000.0/x["win"] for x in r])
    pk=statistics.median([x["pkts"] for x in r])
    print("| {} | {} | {:.1f} | {:.1f}-{:.1f} | {:.1f} | {:.1f} | {:.2f} | {:.0f} | {:.1f} | {:.1f} |".format(
      tag,len(r),statistics.median(p),pct(p,0.1),pct(p,0.9),ipdv,
      statistics.median([x["probe"] for x in r]),statistics.median([x["jit"] for x in r]),pk,fps,pk/fps))
