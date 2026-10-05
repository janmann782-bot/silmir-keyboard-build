#!/usr/bin/env python3
# Sil'mir <-> Russian reference translator, rule-based and offline.
# Built from the user's Sil'mir dictionary + grammar. Standard library only.
from __future__ import annotations
import csv, re, sys, pathlib
from dataclasses import dataclass
from collections import defaultdict

BASE=pathlib.Path(__file__).resolve().parent
LEX=BASE/'assets'/'silmir_translation_lexicon.tsv'
FORMS=BASE/'assets'/'silmir_ru_forms_compact.tsv'

VOWELS=set('aæeəioøuy')
CASE_SUFFIXES=[('ergative','əś'),('accusative','ən'),('directional','iź'),('dative','uv'),('genitive','om'),('instrumental','yr'),('locative','ov'),('ablative','yf')]
NUMBER_SUFFIXES=[('dual','et'),('plural','in')]
TENSE_VOWEL={'past':'a','present':'i','future':'u'}
VOWEL_TENSE={v:k for k,v in TENSE_VOWEL.items()}
PERSON_SUFFIX={('1','sg'):'m',('2','sg'):'ś',('3','sg'):'',('1','pl'):'mn',('2','pl'):'śn',('3','pl'):'n'}
PERSON_PATTERNS=sorted(((s,p,n) for (p,n),s in PERSON_SUFFIX.items()),key=lambda x:len(x[0]),reverse=True)
PRON={
 'cə':('я','меня','мне','мной'), 'pavil':('ты','тебя','тебе','тобой'),
 'hon':('он','его','ему','им'), 'dat':('она','ее','ей','ею'), 'sysæg':('оно','его','ему','им'),
 'mæśærel':('мы','нас','нам','нами'), 'uneź':('вы','вас','вам','вами'), 'ynən':('они','их','им','ими')
}
IRREG_SIL={'tyt':'иди','hremem':'умойся','lavi':'в унитазе'}
FUNCTION_SIL={'na':'не','vizøs':'если','li':'ли','emøg':'кто','hit':'что'}
RU_PREP={'в':'locative','во':'locative','на':'locative','к':'directional','ко':'directional','из':'ablative','от':'ablative','ото':'ablative'}
DUAL_RU={'nominative':'два','accusative':'два','genitive':'двух','dative':'двум','instrumental':'двумя','locative':'двух','ablative':'двух','directional':'двум','ergative':'два'}

TOKEN_RE=re.compile(r"[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+|\d+|[^\w\s]",re.UNICODE)
CYR_RE=re.compile(r'[А-Яа-яЁё]')

def norm_ru(s): return s.lower().replace('ё','е')

RU_TO_SIL_TRANSLIT={
 'а':'a','б':'b','в':'v','г':'g','д':'d','е':'e','ё':'jo','ж':'ź','з':'z','и':'i','й':'j',
 'к':'k','л':'l','м':'m','н':'n','о':'o','п':'p','р':'r','с':'s','т':'t','у':'u','ф':'f','х':'h',
 'ц':'ts','ч':'tś','ш':'ś','щ':'śś','ъ':'','ы':'y','ь':'','э':'e','ю':'ju','я':'ja'
}
def transliterate_ru_to_sil(s):
    return ''.join(RU_TO_SIL_TRANSLIT.get(ch, ch if ch.isalnum() else '') for ch in s.lower())

def is_word(t): return bool(re.match(r'^[A-Za-zÆØƏŚŹæøəśźА-Яа-яЁё]+',t))

@dataclass
class Lex:
    root:str; ru:str; pos:str; synonym_of:str; register:str; aliases:list[str]

@dataclass
class RuForm:
    form:str; root:str; pos:str; number:str; morph:str; lemma:str; priority:int

@dataclass
class SilAnalysis:
    token:str; root:str=''; pos:str='unknown'; number:str='singular'; case:str='absolutive'
    tense:str=''; person:str='3'; person_number:str='sg'; voice:str='active'; register:str='ordinary'
    derivation:str=''; ru:str=''; confidence:int=0

class Translator:
    def __init__(self):
        self.lex={}
        self.ru_forms=defaultdict(list)
        with LEX.open(encoding='utf-8') as f:
            for r in csv.DictReader(f,delimiter='\t'):
                e=Lex(r['root'],r['russian'],r['pos'],r['synonym_of'],r['register'],[x for x in r['aliases'].split('|') if x])
                self.lex[e.root]=e
        with FORMS.open(encoding='utf-8') as f:
            for r in csv.DictReader(f,delimiter='\t'):
                rf=RuForm(r['form'],r['root'],r['pos'],r['number'],r['morph'],r['lemma'],int(r['priority']))
                self.ru_forms[norm_ru(rf.form)].append(rf)
        for vals in self.ru_forms.values(): vals.sort(key=lambda x:(x.priority, 1 if self.lex.get(x.root,Lex('','','','','',[])).synonym_of else 0, len(x.root)))

    def translate(self,text,direction='auto'):
        if direction=='auto': direction='ru2sil' if CYR_RE.search(text) else 'sil2ru'
        return self.ru_to_sil(text) if direction=='ru2sil' else self.sil_to_ru(text)

    # ---------- Sil'mir morphology ----------
    def analyze_sil(self,token):
        low=token.lower()
        if low in PRON:
            return SilAnalysis(token,low,'pronoun',ru=PRON[low][0],confidence=100)
        if low in FUNCTION_SIL:
            return SilAnalysis(token,low,'particle',ru=FUNCTION_SIL[low],confidence=100)
        if low in IRREG_SIL:
            return SilAnalysis(token,low,'irregular',ru=IRREG_SIL[low],confidence=100)

        candidates=[]
        # Noun/case analyses. The root must exist, preventing blind suffix chopping.
        cases=[('absolutive','')]+CASE_SUFFIXES
        nums=[('singular','')]+NUMBER_SUFFIXES
        for cname,cs in cases:
            if cs and not low.endswith(cs): continue
            stem1=low[:-len(cs)] if cs else low
            for nname,ns in nums:
                if ns and not stem1.endswith(ns): continue
                root=stem1[:-len(ns)] if ns else stem1
                e=self.lex.get(root)
                if e and e.pos in ('noun','pronoun','numeral','unknown'):
                    score=80 + (5 if cs else 0)+(4 if ns else 0) + (5 if not e.synonym_of else 0)
                    candidates.append(SilAnalysis(token,root,e.pos,nname,cname,ru=e.ru,confidence=score))

        # Adjective number agreement.
        for nname,ns in nums:
            if ns and not low.endswith(ns): continue
            root=low[:-len(ns)] if ns else low
            e=self.lex.get(root)
            if e and e.pos=='adjective':
                candidates.append(SilAnalysis(token,root,'adjective',nname,'absolutive',ru=e.ru,confidence=86+(4 if ns else 0)))

        # Verb finite: root + tense + [voice] + person-number + register.
        # Active examples are fully specified in the source. Passive/reflexive are accepted by the documented slot order.
        for reg,rs in [('ordinary','s'),('respectful','l')]:
            if not low.endswith(rs): continue
            body=low[:-1]
            for voice,vs in [('active',''),('passive','s'),('reflexive','m')]:
                for ps,pers,pnum in PERSON_PATTERNS:
                    tail=vs+ps
                    if tail and not body.endswith(tail): continue
                    b2=body[:-len(tail)] if tail else body
                    if not b2: continue
                    tv=b2[-1]
                    if tv not in VOWEL_TENSE: continue
                    root=b2[:-1]
                    e=self.lex.get(root)
                    if e and e.pos=='verb':
                        candidates.append(SilAnalysis(token,root,'verb',tense=VOWEL_TENSE[tv],person=pers,person_number=pnum,voice=voice,register=reg,ru=e.ru,confidence=95 if voice=='active' else 82))

        # Imperative, participles, gerund.
        for suf,kind in [('øjm','imperative_reflexive'),('øj','imperative'),('suk','passive_participle'),('uk','participle'),('eb','gerund')]:
            if low.endswith(suf):
                root=low[:-len(suf)]; e=self.lex.get(root)
                if e and e.pos=='verb': candidates.append(SilAnalysis(token,root,'verb',derivation=kind,ru=e.ru,confidence=92))

        # Productive derivations.
        derivs=[('yvm','superlative'),('yvs','excessive'),('yv','comparative'),('yś','attenuative'),('of','adverb'),('yb','adjective_from_noun'),('yn','agent'),('yl','action_result'),('ic','diminutive'),('uz','augmentative'),('av','collective'),('t','ordinal')]
        for suf,kind in derivs:
            if low.endswith(suf):
                root=low[:-len(suf)]; e=self.lex.get(root)
                if e: candidates.append(SilAnalysis(token,root,'derived',derivation=kind,ru=e.ru,confidence=74))

        # Exact lexical candidate is deliberately not always dominant: forms such as domin can collide with lexical roots.
        e=self.lex.get(low)
        if e:
            candidates.append(SilAnalysis(token,low,e.pos,ru=e.ru,confidence=84 + (5 if not e.synonym_of else 0)))
        if not candidates: return SilAnalysis(token,low,'unknown',ru=token,confidence=0)
        candidates.sort(key=lambda a:(a.confidence,len(a.root)),reverse=True)
        return candidates[0]

    def build_sil_verb(self,root,tense='present',person='3',number='sg',voice='active',register='ordinary'):
        return root+TENSE_VOWEL.get(tense,'i')+({'active':'','passive':'s','reflexive':'m'}.get(voice,''))+PERSON_SUFFIX.get((person,number),'')+('l' if register=='respectful' else 's')

    def build_sil_noun(self,root,number='singular',case='absolutive'):
        ns={'singular':'','dual':'et','plural':'in'}.get(number,'')
        cs=dict(CASE_SUFFIXES).get(case,'')
        return root+ns+cs

    # ---------- Russian morphology helpers ----------
    def ru_lookup(self,word,pos=None):
        vals=self.ru_forms.get(norm_ru(word),[])
        if pos: vals=[x for x in vals if x.pos==pos]
        return vals[0] if vals else None

    def noun_forms(self,lemma):
        # same compact declension model used to build the reverse-form index
        l=norm_ru(lemma); hush=set('гкхжчшщц')
        if not re.match(r'^[а-я-]+$',l): return {('sg','nom'):lemma}
        if l=='камень':
            return {('sg','nom'):'камень',('sg','gen'):'камня',('sg','dat'):'камню',('sg','acc'):'камень',('sg','ins'):'камнем',('sg','loc'):'камне',('pl','nom'):'камни',('pl','gen'):'камней',('pl','dat'):'камням',('pl','acc'):'камни',('pl','ins'):'камнями',('pl','loc'):'камнях'}
        if l.endswith('а'):
            st=l[:-1]; soft=st[-1:] in hush; gen=st+('и' if soft else 'ы'); dat=st+'е'; acc=st+'у'; ins=st+'ой'; loc=st+'е'; plnom=st+('и' if soft else 'ы'); plgen=st; pldat=st+'ам'; plins=st+'ами'; plloc=st+'ах'
        elif l.endswith('я'):
            st=l[:-1]; gen=st+'и'; dat=st+'е'; acc=st+'ю'; ins=st+'ей'; loc=st+'е'; plnom=st+'и'; plgen=st; pldat=st+'ям'; plins=st+'ями'; plloc=st+'ях'
        elif l.endswith('о'):
            st=l[:-1]; gen=st+'а'; dat=st+'у'; acc=l; ins=st+'ом'; loc=st+'е'; plnom=st+'а'; plgen=st; pldat=st+'ам'; plins=st+'ами'; plloc=st+'ах'
        elif l.endswith('е'):
            st=l[:-1]; gen=st+'я'; dat=st+'ю'; acc=l; ins=st+'ем'; loc=st+'е'; plnom=st+'я'; plgen=st+'й'; pldat=st+'ям'; plins=st+'ями'; plloc=st+'ях'
        elif l.endswith('й'):
            st=l[:-1]; gen=st+'я'; dat=st+'ю'; acc=l; ins=st+'ем'; loc=st+'е'; plnom=st+'и'; plgen=st+'ев'; pldat=st+'ям'; plins=st+'ями'; plloc=st+'ях'
        elif l.endswith('ь'):
            st=l[:-1]; gen=st+'я'; dat=st+'ю'; acc=l; ins=st+'ем'; loc=st+'е'; plnom=st+'и'; plgen=st+'ей'; pldat=st+'ям'; plins=st+'ями'; plloc=st+'ях'
        else:
            st=l; gen=st+'а'; dat=st+'у'; acc=l; ins=st+'ом'; loc=st+'е'; plnom=st+('и' if st[-1:] in hush else 'ы'); plgen=st+'ов'; pldat=st+'ам'; plins=st+'ами'; plloc=st+'ах'
        return {('sg','nom'):l,('sg','gen'):gen,('sg','dat'):dat,('sg','acc'):acc,('sg','ins'):ins,('sg','loc'):loc,('pl','nom'):plnom,('pl','gen'):plgen,('pl','dat'):pldat,('pl','acc'):plnom,('pl','ins'):plins,('pl','loc'):plloc}

    def verb_forms(self,lemma):
        l=norm_ru(lemma)
        irr={
          'видеть':['вижу','видишь','видит','видим','видите','видят'],
          'давать':['даю','даешь','дает','даем','даете','дают'],
          'идти':['иду','идешь','идет','идем','идете','идут'],
          'смотреть':['смотрю','смотришь','смотрит','смотрим','смотрите','смотрят'],
          'существовать':['существую','существуешь','существует','существуем','существуете','существуют']}
        keys=[('1','sg'),('2','sg'),('3','sg'),('1','pl'),('2','pl'),('3','pl')]
        if l in irr: return {('present',)+k:v for k,v in zip(keys,irr[l])}
        refl=l.endswith('ся') or l.endswith('сь'); base=l[:-2] if refl else l; arr=[]
        if base.endswith('овать'):
            st=base[:-5]; arr=[st+'ую',st+'уешь',st+'ует',st+'уем',st+'уете',st+'уют']
        elif base.endswith('ить'):
            st=base[:-3]; arr=[st+'ю',st+'ишь',st+'ит',st+'им',st+'ите',st+'ят']
        elif base.endswith(('ать','ять','еть')):
            st=base[:-2]; arr=[st+'ю',st+'ешь',st+'ет',st+'ем',st+'ете',st+'ют']
        out={}
        if arr:
            for k,v in zip(keys,arr): out[('present',)+k]=v+('сь' if refl else '')
        if base.endswith('ть'):
            st=base[:-2]; out[('past','3','sg')]=st+'л'+('ся' if refl else ''); out[('past','3','pl')]=st+'ли'+('сь' if refl else '')
        return out

    def ru_inflect_noun(self,lemma,number,case):
        f=self.noun_forms(lemma)
        rcase={'absolutive':'nom','ergative':'nom','accusative':'acc','dative':'dat','genitive':'gen','instrumental':'ins','locative':'loc','ablative':'gen','directional':'dat'}.get(case,'nom')
        if number=='dual':
            # Russian numeral government
            numword=DUAL_RU.get(case,'два')
            nouncase='gen' if case in ('absolutive','ergative','accusative','genitive') else rcase
            nounnum='sg' if case in ('absolutive','ergative','accusative') else 'pl'
            return numword+' '+f.get((nounnum,nouncase),lemma)
        return f.get(('pl' if number=='plural' else 'sg',rcase),lemma)

    def ru_pronoun(self,root,case):
        a=PRON[root]
        if case=='dative' or case=='directional': return a[2]
        if case=='instrumental': return a[3]
        if case in ('accusative','genitive','ablative'): return a[1]
        return a[0]

    def sil_analysis_to_ru(self,a):
        if a.pos=='unknown': return a.token
        if a.pos=='particle' or a.pos=='irregular': return a.ru
        if a.pos=='pronoun':
            s=self.ru_pronoun(a.root,a.case)
            if a.case=='directional': return 'к '+s
            if a.case=='ablative': return 'от '+s
            return s
        e=self.lex.get(a.root); lemma=(e.aliases[0] if e and e.aliases else (e.ru if e else a.ru))
        lemma=re.sub(r'\s*\([^\)]*\)\s*','',lemma).strip()
        if a.pos=='noun' or a.pos=='numeral':
            s=self.ru_inflect_noun(lemma,a.number,a.case)
            if a.case=='locative': s='в '+s
            elif a.case=='ablative': s='из '+s
            elif a.case=='directional': s='к '+s
            return s
        if a.pos=='adjective':
            if a.number=='plural':
                if lemma.endswith(('ый','ой')): return lemma[:-2]+'ые'
                if lemma.endswith('ий'): return lemma[:-2]+'ие'
            return lemma
        if a.pos=='verb':
            if a.derivation=='imperative' or a.derivation=='imperative_reflexive':
                # crude imperative from infinitive; explicit irregulars are handled before this.
                if lemma.endswith('ить'): return lemma[:-3]+'и'
                if lemma.endswith('ть'): return lemma[:-2]+'й'
                return lemma
            if a.derivation=='gerund': return lemma+' (деепр.)'
            if a.derivation in ('participle','passive_participle'): return lemma+' (прич.)'
            if a.tense=='future':
                fut={('1','sg'):'буду',('2','sg'):'будешь',('3','sg'):'будет',('1','pl'):'будем',('2','pl'):'будете',('3','pl'):'будут'}[(a.person,a.person_number)]
                return fut+' '+lemma
            vf=self.verb_forms(lemma)
            return vf.get((a.tense or 'present',a.person,a.person_number),lemma)
        if a.pos=='derived':
            d=a.derivation
            if d=='diminutive': return 'маленький '+lemma
            if d=='augmentative': return 'огромный '+lemma
            if d=='collective': return 'скопление '+lemma
            if d=='adjective_from_noun': return 'относящийся к '+lemma
            if d=='agent': return 'деятель: '+lemma
            if d=='action_result': return 'результат/действие: '+lemma
            if d=='comparative': return 'более '+lemma
            if d=='superlative': return 'самый '+lemma
            if d=='attenuative': return 'слегка '+lemma
            if d=='excessive': return 'слишком '+lemma
            if d=='adverb': return lemma+' образом'
            if d=='ordinal': return 'порядковый: '+lemma
        return lemma

    # ---------- Sil'mir -> Russian sentence ----------
    def sil_to_ru(self,text):
        toks=TOKEN_RE.findall(text)
        analyses=[self.analyze_sil(t) if is_word(t) else None for t in toks]
        out=[]; i=0
        while i<len(toks):
            a=analyses[i]
            if a is None:
                out.append(toks[i]); i+=1; continue
            # Possessor precedes noun in Sil'mir; Russian normally reverses it.
            if a.case=='genitive' and i+1<len(toks) and analyses[i+1] and analyses[i+1].pos in ('noun','pronoun'):
                head=self.sil_analysis_to_ru(analyses[i+1]); poss=self.sil_analysis_to_ru(a)
                out.extend([head,poss]); i+=2; continue
            out.append(self.sil_analysis_to_ru(a)); i+=1
        s=self.join_tokens(out)
        return self.capitalize_like(text,s)

    # ---------- Russian -> Sil'mir sentence ----------
    def ru_to_sil(self,text):
        toks=TOKEN_RE.findall(text)
        info=[]
        for t in toks:
            if not is_word(t): info.append(None); continue
            vals=self.ru_forms.get(norm_ru(t),[])
            info.append(vals[0] if vals else None)
        # finite verb
        verb_idx=next((i for i,x in enumerate(info) if x and x.pos=='verb' and x.morph.startswith('verb:')),None)
        subj_idx=None; obj_idx=None
        if verb_idx is not None:
            for i in range(verb_idx-1,-1,-1):
                if info[i] and info[i].pos in ('noun','pronoun'):
                    subj_idx=i; break
            # first unprepositioned noun/pronoun after verb
            for i in range(verb_idx+1,len(toks)):
                if info[i] and info[i].pos in ('noun','pronoun'):
                    prev=norm_ru(toks[i-1]) if i>0 else ''
                    if prev not in RU_PREP:
                        obj_idx=i; break
        direct_object=obj_idx is not None
        # mark dual nouns following forms of два
        dual_next={}
        for i,t in enumerate(toks[:-1]):
            nt=norm_ru(t)
            if nt in ('два','две','двух','двум','двумя'):
                dual_next[i+1]={'два':'absolutive','две':'absolutive','двух':'genitive','двум':'dative','двумя':'instrumental'}[nt]

        out=[]; skip=set()
        for i,t in enumerate(toks):
            if i in skip: continue
            if not is_word(t): out.append(t); continue
            nt=norm_ru(t)
            if nt in RU_PREP:
                # preposition is encoded by a case suffix on the next noun
                continue
            if nt in ('два','две','двух','двум','двумя'):
                continue
            rf=info[i]
            if not rf:
                out.append(transliterate_ru_to_sil(t)); continue
            root=rf.root
            if rf.pos=='particle': out.append(root); continue
            if rf.pos=='numeral': out.append(root); continue
            if rf.pos=='verb':
                morph=rf.morph.split(':')
                tense=morph[1] if len(morph)>1 else 'present'; person=morph[2] if len(morph)>2 else '3'; num=rf.number
                if subj_idx is not None and info[subj_idx]:
                    sr=info[subj_idx]
                    num='pl' if sr.number=='pl' else 'sg'
                    if sr.root=='cə': person='1'
                    elif sr.root=='pavil': person='2'
                    elif sr.root=='mæśærel': person='1'; num='pl'
                    elif sr.root=='uneź': person='2'; num='pl'
                    elif sr.root=='ynən': person='3'; num='pl'
                    else: person='3'
                out.append(self.build_sil_verb(root,tense,person,num)); continue
            if rf.pos in ('noun','pronoun'):
                number='plural' if rf.number=='pl' else 'singular'; case='absolutive'
                if i in dual_next: number='dual'; case=dual_next[i]
                prev=norm_ru(toks[i-1]) if i>0 else ''
                prep=prev
                if prep not in RU_PREP and i in dual_next and i>1:
                    prep=norm_ru(toks[i-2])
                if prep in RU_PREP: case=RU_PREP[prep]
                elif i==obj_idx: case='accusative'
                elif i==subj_idx and direct_object and rf.pos=='noun': case='ergative'
                else:
                    cmap={'gen':'genitive','dat':'dative','acc':'accusative','ins':'instrumental','loc':'locative'}
                    if rf.morph in cmap: case=cmap[rf.morph]
                if rf.pos=='pronoun':
                    out.append(root+('ən' if case=='accusative' else ''))
                else: out.append(self.build_sil_noun(root,number,case))
                continue
            if rf.pos=='adjective':
                # agree with immediately following noun/dual marker if detectable
                num='singular'
                if i+1 in dual_next: num='dual'
                elif i+1<len(info) and info[i+1] and info[i+1].number=='pl': num='plural'
                out.append(root+({'singular':'','dual':'et','plural':'in'}[num])); continue
            out.append(root)
        s=self.join_tokens(out)
        return self.capitalize_like(text,s)

    @staticmethod
    def join_tokens(parts):
        s=''
        for p in parts:
            if not p: continue
            if re.match(r'^[,.;:!?\)\]]$',p): s=s.rstrip()+p
            elif re.match(r'^[\(\[]$',p): s+=(' ' if s and not s.endswith(' ') else '')+p
            else: s+=(' ' if s and not s.endswith((' ','(','[')) else '')+p
        return s.strip()

    @staticmethod
    def capitalize_like(src,out):
        if src[:1].isupper() and out:
            return out[0].upper()+out[1:]
        return out

if __name__=='__main__':
    tr=Translator()
    if len(sys.argv)>=3:
        print(tr.translate(' '.join(sys.argv[2:]),sys.argv[1]))
    else:
        tests=['Я вижу камень','Король дает воду','Я вижу два камня','Я не вижу камень','cə velims kairən','cufəś daris lumən','cufinəś velins kairinən','domov','domyf','domiź','cufom kair']
        for x in tests: print(x,'=>',tr.translate(x))
