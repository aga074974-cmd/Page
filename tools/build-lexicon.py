#!/usr/bin/env python3
"""ساختِ فایلِ واژگانِ اپ از دو منبعِ باز.

    curl -LO https://raw.githubusercontent.com/roshan-research/hazm/master/hazm/data/words.dat
    curl -LO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/fa/fa_50k.txt
    python3 tools/build-lexicon.py words.dat fa_50k.txt app/src/main/assets/lexicon/fa-words.txt

دو منبع مکمل‌اند: hazm واژگانِ *اسمیِ* کتابی را دارد (و هیچ صورتِ صرف‌شدهٔ فعل
ندارد) و پیکرهٔ زیرنویس صورت‌های فعل و بسامدِ کاربردِ واقعی را. جزئیات و مجوزها
در app/src/main/assets/lexicon/README.txt

ریشه‌های فعل از خودِ PersianVerbStems.kt خوانده می‌شوند تا این اسکریپت و اپ
هیچ‌وقت از هم جدا نیفتند.
"""
import math, re, os, sys
ZWNJ='‌'
LETTERS=set('اآبپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءأإئؤة')
NORM={'ي':'ی','ى':'ی','ې':'ی','ۍ':'ی','ك':'ک','ڪ':'ک','ګ':'ک','ة':'ه','ۀ':'ه','ہ':'ه','ە':'ه',
      'أ':'ا','إ':'ا','ٱ':'ا','ٲ':'ا','ٳ':'ا','ٵ':'ا','ۋ':'و'}
STRIP=set(chr(c) for c in range(0x064B,0x0660))|{'ٰ','ـ','​','‌','‍','‎','‏','؜'}
def key(w): return ''.join(NORM.get(c,c) for c in w if c not in STRIP)

src=open('app/src/main/java/ir/page/persianocr/text/PersianVerbStems.kt',encoding='utf-8').read()
def lst(name, kind='Set'):
    blk=re.search(rf'val {name}: {kind}<String> = (?:setOf|listOf)\((.*?)\n    \)', src, re.S).group(1)
    return set(re.findall(r'"([^"]+)"', blk))
PRESENT, PAST = lst('PRESENT'), lst('PAST')
ENDINGS = re.findall(r'"([^"]+)"', re.search(r'val ENDINGS: List<String> = listOf\((.*?)\)', src, re.S).group(1))
PREFIXES = re.findall(r'"([^"]+)"', re.search(r'val PREFIXES: List<String> = listOf\((.*?)\)', src, re.S).group(1))
def conjugated(s):
    if s in PAST: return True
    return any(s.endswith(e) and s[:-len(e)] and (s[:-len(e)] in PRESENT or s[:-len(e)] in PAST) for e in ENDINGS)
def verb_form(w):
    return conjugated(w) or any(w.startswith(p) and len(w[len(p):])>=2 and conjugated(w[len(p):]) for p in PREFIXES)

hz={}; certified=set()
for line in open(sys.argv[1],encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)<3: continue
    k=key(p[0])
    if len(k)<2 or not all(c in LETTERS for c in k): continue
    hz[k]=max(hz.get(k,0), int(p[1]) if p[1].isdigit() else 0)
    if p[2]!='0': certified.add(k)

sub={}
for line in open(sys.argv[2],encoding='utf-8'):
    p=line.split()
    if len(p)!=2: continue
    k=key(p[0]); c=int(p[1])
    if c<15 or len(k)<2 or not all(ch in LETTERS for ch in k): continue
    sub[k]=sub.get(k,0)+c

SCALE=sum(hz.values())/sum(sub.values())
def band(c): return 0 if c<=0 else max(0,min(9,round(math.log10(c)*2)))
words={k: max(band(sub.get(k,0)), band(hz.get(k,0)/SCALE)) for k in set(sub)|set(hz)}
dropped=[k for k in list(words) if k not in certified and words[k]<=5 and not verb_form(k)]
for k in dropped: del words[k]

out=sorted(words)
with open(sys.argv[3],'w',encoding='utf-8') as f:
    f.write('\n'.join(f"{w} {words[w]}" for w in out)+'\n')
print(f"واژه‌ها {len(out):,} • بایت {os.path.getsize(sys.argv[3]):,} • حذف‌شده {len(dropped):,}")
for w in ["یول","جه","میک","وید","بگویند","گویند","میگوید","هستند","بکنید","نگفت","نگرش","محرک","متقاعدسازی","بول","پول"]:
    print(f"  {w:<12} {words.get(w,'—')}")
