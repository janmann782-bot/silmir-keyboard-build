#!/usr/bin/env python3
"""Compile Sil'mir root lexicon into a concept layer for Translator v7.

The source of truth is assets/silmir_translation_lexicon.tsv.  This script is
run at build time, so the Android APK receives a deterministic concept index
without requiring the original markdown archive.
"""
from pathlib import Path
import csv, re

BASE=Path(__file__).resolve().parents[1]
LEX=BASE/'assets/silmir_translation_lexicon.tsv'
OUT=BASE/'assets/silmir_concepts_v7.tsv'
OVR=BASE/'assets/silmir_concept_overrides_v7.tsv'

overrides={
    # Project-canonical meanings supplied by the author.
    'kevir': ('кефир', ['кефир','кефир напиток','кефир питье']),
    'Kevirytsyt': ('кефирстан', ['кефирстан']),
    'møcok': ('время', ['время','период','срок']),
}
classifiers={'напиток','питье','пища','еда','город','страна','государство','река','озеро','гора','растение','животное','птица','рыба','гриб','дерево','материал','оружие','профессия','болезнь','чувство','предмет','человек','место','инструмент','термин','явление'}

def norm(s): return re.sub(r'\s+',' ',s.strip().lower().replace('ё','е'))
def clean(s):
    s=re.sub(r'\s+',' ',s.strip(' \t|;,.'))
    if s.count('(')>s.count(')'): s=s.split('(',1)[0].strip()
    return s

def canonical(russian,aliases):
    candidates=[russian]+aliases
    for c in candidates:
        c=clean(re.sub(r'\s*\([^)]*\)\s*',' ',c))
        if not c: continue
        words=c.split()
        if len(words)==2 and norm(words[1]) in classifiers: return words[0]
        return c
    return ''

rows=[]
with LEX.open(encoding='utf-8') as f:
    for i,r in enumerate(csv.DictReader(f,delimiter='\t')):
        root=r['root']
        als=[clean(x) for x in r.get('aliases','').split('|') if clean(x)]
        can=canonical(r.get('russian',''),als)
        if root in overrides:
            can, extra=overrides[root]; als=extra+als
        for x in (can,clean(r.get('russian',''))):
            if x: als.append(x)
        uniq=[];seen=set()
        for a in als:
            k=norm(a)
            if not k or k in seen: continue
            seen.add(k);uniq.append(a)
        rows.append({'concept_id':str(i),'root':root,'pos':r['pos'],'canonical_ru':can,
                     'register':r.get('register','standard') or 'standard',
                     'synonym_of':r.get('synonym_of',''),'aliases':'|'.join(uniq)})

with OUT.open('w',encoding='utf-8',newline='') as f:
    w=csv.DictWriter(f,fieldnames=['concept_id','root','pos','canonical_ru','register','synonym_of','aliases'],delimiter='\t',lineterminator='\n')
    w.writeheader();w.writerows(rows)
with OVR.open('w',encoding='utf-8',newline='') as f:
    w=csv.writer(f,delimiter='\t',lineterminator='\n');w.writerow(['root','canonical_ru','aliases','reason'])
    for root,(can,als) in overrides.items():w.writerow([root,can,'|'.join(als),'project canonical override'])
print(f'compiled {len(rows)} Sil\'mir concepts -> {OUT}')
