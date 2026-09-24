"""Reference implementation of slot rules (docs/03 §2). Run from repo root: python3 docs/reference/schedule_reference.py — validates docs/schedule-vectors.json. Naive local datetimes (timezone has no DST in America/Caracas); production code must use real tz handling."""
import json
from datetime import datetime, timedelta, date
V=json.load(open('docs/schedule-vectors.json'))
base=V['baseSettings']
def S(o=None): s=dict(base); s.update(o or {}); return s
def hm(d,t): h,m=map(int,t.split(':')); return datetime(d.year,d.month,d.day,h,m)
def slots(d,s):
    if not s['enabled'] or d.isoweekday() not in s['days']: return []
    t=hm(d,s['startTime']); e=hm(d,s['endTime']); out=[]
    while t<e: out.append(t); t+=timedelta(minutes=s['intervalMinutes'])
    return out
def nxt(now,s):
    for k in range(8):
        for t in slots((now+timedelta(days=k)).date(),s):
            if t>now: return t
def assign(c,s):
    half=timedelta(minutes=s['intervalMinutes']/2); best=None
    for t in slots(c.date(),s):
        d=abs(c-t)
        if d<=half and (best is None or d<abs(c-best)): best=t
    return best
def status(t,now,cks,s):
    esc=timedelta(minutes=s['escalationMinutes'])
    mine=sorted([c for c in cks if assign(c[0],s)==t and c[0]<=now])
    if mine:
        c,cr=mine[0]; return ('on_time' if c<=t+esc else 'late'), (cr-c).total_seconds()>120
    if t>now: return 'upcoming',False
    return ('pending' if now<t+esc else 'missed'),False
P=datetime.fromisoformat; ok=True
def chk(id,got,exp):
    global ok
    if got!=exp: ok=False; print('FAIL',id,got,exp)
    else: print('ok',id)
for v in V['nextSlot']:
    r=nxt(P(v['now']),S(v.get('settings'))); chk(v['id'], r.isoformat() if r else None, v['expected'])
for v in V['assign']:
    s=S(v.get('settings')); c=P(v['clientAt']); t=assign(c,s)
    chk(v['id'], t.strftime('%H:%M') if t else None, v['expectedSlot'])
    if t: chk(v['id']+'s', status(t,c+timedelta(minutes=1),[(c,c)],s)[0], v['expectedStatus'])
for v in V['slotStatus']:
    s=S(); d=date.fromisoformat(v['date']); t=hm(d,v['slot'])
    st,sl=status(t,P(v['now']),[(P(c['clientAt']),P(c['createdAt'])) for c in v['checkins']],s)
    chk(v['id'],st,v['expectedStatus'])
    if 'expectedSyncedLate' in v: chk(v['id']+'L',sl,v['expectedSyncedLate'])
for v in V['recompute']:
    s=S(); d=date.fromisoformat(v['date'])
    for i,st in enumerate(v['steps']):
        cks=[(P(c['clientAt']),P(c['createdAt'])) for c in st['checkins']]
        for k,e in st['expect'].items(): chk(f"{v['id']}.{i}.{k}", status(hm(d,k),P(st['now']),cks,s)[0], e)
        for k in st.get('expectSyncedLate',[]): chk(f"{v['id']}.{i}.{k}L", status(hm(d,k),P(st['now']),cks,s)[1], True)
for v in V['slotsFor']:
    sl=[t.strftime('%H:%M') for t in slots(date.fromisoformat(v['date']),S(v.get('settings')))]
    if 'expected' in v: chk(v['id'],sl,v['expected'])
    else: chk(v['id'],(len(sl),sl[0],sl[-1]),(v['expectedCount'],v['expectedFirst'],v['expectedLast']))
print('ALL OK' if ok else 'FAILURES')
