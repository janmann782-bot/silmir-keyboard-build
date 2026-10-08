#!/usr/bin/env python3
# Sil'mir <-> Russian MEGA reference translator.
# Offline, rule-based, standard library only. Mirrors the Android Java engine.
from __future__ import annotations
import csv, re, sys, pathlib
from dataclasses import dataclass
from collections import defaultdict

BASE=pathlib.Path(__file__).resolve().parent
LEX=BASE/'assets'/'silmir_translation_lexicon.tsv'
FORMS=BASE/'assets'/'silmir_ru_forms_mega.tsv'
ALIASES=BASE/'assets'/'silmir_semantic_aliases_mega.tsv'

CASE_SUFFIXES=[('ergative','əś'),('accusative','ən'),('directional','iź'),('dative','uv'),('genitive','om'),('instrumental','yr'),('locative','ov'),('ablative','yf')]
NUMBER_SUFFIXES=[('dual','et'),('plural','in')]
TENSE_VOWEL={'past':'a','present':'i','future':'u'}
VOWEL_TENSE={v:k for k,v in TENSE_VOWEL.items()}
PERSON_SUFFIX={('1','sg'):'m',('2','sg'):'ś',('3','sg'):'',('1','pl'):'mn',('2','pl'):'śn',('3','pl'):'n'}
PERSON_PATTERNS=sorted(((s,p,n) for (p,n),s in PERSON_SUFFIX.items()),key=lambda x:len(x[0]),reverse=True)
VOWELS=set('aæeəioøuy')

PRON={
 'cə':('я','меня','мне','мной'), 'pavil':('ты','тебя','тебе','тобой'),
 'hon':('он','его','ему','им'), 'dat':('она','ее','ей','ею'), 'sysæg':('оно','его','ему','им'),
 'mæśærel':('мы','нас','нам','нами'), 'uneź':('вы','вас','вам','вами'), 'ynən':('они','их','им','ими')
}
IRREG_SIL={'tyt':'иди','hremem':'умойся','lavi':'в унитазе'}
FUNCTION_SIL={'na':'не','vizøs':'если бы','li':'','emøg':'кто','hit':'что','najem':'никто'}
DUAL_RU={'absolutive':'два','ergative':'два','accusative':'два','genitive':'двух','dative':'двум','instrumental':'двумя','locative':'двух','ablative':'двух','directional':'двум'}

PREP_LOC_DIR={'в','во','на'}
PREP_DIRECTIONAL={'к','ко'}
PREP_ABLATIVE={'из','от','ото','с','со'}
PREP_LOCATIVE={'при'}
SUPPORTED_PREPS=PREP_LOC_DIR|PREP_DIRECTIONAL|PREP_ABLATIVE|PREP_LOCATIVE
PREP_SCAN=SUPPORTED_PREPS|{'за'}
VIEW_LEMMAS={'смотреть','глядеть','наблюдать','посмотреть','взглянуть','следить'}
MOTION_LEMMAS={'идти','ходить','ехать','бежать','лететь','плыть','двигаться','направляться','приходить','уходить','входить','выходить','подходить','отходить','пойти','прийти','уйти','подойти','отойти','выйти','войти'}
TOWARD_LEMMAS={'подходить','подойти','приходить','прийти','приближаться','войти'}
AWAY_LEMMAS={'уходить','уйти','отходить','отойти','удаляться','выйти'}
HABITUAL={'обычно','регулярно','постоянно'}
INTENSIFIERS={'очень','крайне','весьма'}
VERB_INTENSIFIERS={'пристально'}
DEGREES={'более':'comparative','самый':'superlative','самая':'superlative','самое':'superlative','самые':'superlative','слегка':'attenuative','слишком':'excessive'}
WH_RU={'кто','кого','кому','кем','что','чего','чему','чем','где','куда','откуда','когда','почему','зачем','как','какой','какая','какое','какие','сколько'}
FUTURE_AUX={'буду':('1','sg'),'будешь':('2','sg'),'будет':('3','sg'),'будем':('1','pl'),'будете':('2','pl'),'будут':('3','pl')}
NUM2={'два','две','двух','двум','двумя'}
NUM_PLURAL={'три','четыре','пять','шесть','семь','восемь','девять','десять'}

TOKEN_RE=re.compile(r"[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+|\d+|[^\w\s]",re.UNICODE)
CYR_RE=re.compile(r'[А-Яа-яЁё]')
SIL_SPECIAL_RE=re.compile(r'[ÆØƏŚŹæøəśź]')

RU_TO_SIL={'а':'a','б':'b','в':'v','г':'g','д':'d','е':'e','ё':'jo','ж':'ź','з':'z','и':'i','й':'j','к':'k','л':'l','м':'m','н':'n','о':'o','п':'p','р':'r','с':'s','т':'t','у':'u','ф':'f','х':'h','ц':'ts','ч':'tś','ш':'ś','щ':'śś','ъ':'','ы':'y','ь':'','э':'e','ю':'ju','я':'ja'}
SIL_TO_RU_MULTI=[('śś','щ'),('tś','ч'),('ts','ц')]
SIL_TO_RU={'a':'а','æ':'ай','b':'б','d':'д','c':'к','e':'е','ə':'э','f':'ф','g':'г','h':'х','i':'и','j':'й','k':'к','l':'л','m':'м','n':'н','o':'о','ø':'ой','p':'п','r':'р','s':'с','ś':'ш','t':'т','u':'у','v':'в','y':'ы','z':'з','ź':'ж'}

def norm_ru(s): return s.lower().replace('ё','е').strip()
def is_word(t): return bool(re.fullmatch(r"[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+",t))
def transliterate_ru_to_sil(s): return ''.join(RU_TO_SIL.get(ch,ch if ch.isalnum() else '') for ch in s.lower())
def transliterate_sil_to_ru(s):
    x=s.lower(); out=''; i=0
    while i<len(x):
        hit=False
        for src,dst in SIL_TO_RU_MULTI:
            if x.startswith(src,i): out+=dst; i+=len(src); hit=True; break
        if hit: continue
        out+=SIL_TO_RU.get(x[i],x[i]); i+=1
    return out

def russian_stem(s):
    s=norm_ru(s)
    if ' ' in s:return s
    suff=['иями','ями','ами','ьего','ьему','его','ого','ему','ому','ими','ыми','ешь','ишь','ете','ите','ую','юю','ей','ой','ий','ый','ая','яя','ое','ее','ые','ие','ых','их','ым','им','ом','ем','ах','ях','ам','ям','ов','ев','ть','ти','чь','ся','сь','ала','али','ила','или','ала','яло','али','ал','ил','ел','ела','ели','ет','ит','ут','ют','ат','ят','ы','и','а','я','у','ю','о','е','ь','й']
    for x in suff:
        if len(s)-len(x)>=3 and s.endswith(x):return s[:-len(x)]
    return s

def latin_stem(s):
    s=s.lower().strip()
    if ' ' in s:return s
    suff=['iyami','yami','ami','ogo','ego','emu','omu','imi','ymi','esh','ish','ete','ite','uyu','yuyu','aya','yaya','oe','ee','ye','ie','ykh','ikh','ym','im','om','em','akh','yakh','am','yam','ov','ev','ey','sya','ala','ali','ila','ili','al','il','et','it','ut','yut','at','yat','iy','yy','oy','ti','t','a','i','y','u','o','e']
    for x in suff:
        if len(s)-len(x)>=3 and s.endswith(x):return s[:-len(x)]
    return s

def looks_latin_russian(s):
    x=s.lower()
    return bool(re.search(r'(?:shch|zh|kh|ts|ch|sh|ya|yu|yo)',x) or re.search(r'(?:ogo|emu|ami|ymi|aya|oe|ie|stvo|nie|skiy|skaya|ovat|ivat|it|at|yat|ost|enie)$',x))

@dataclass
class Lex:
    root:str; ru:str; pos:str; synonym:str; register:str; aliases:list[str]
@dataclass
class RuForm:
    form:str; latin:str; root:str; pos:str; number:str; morph:str; lemma:str; priority:int
@dataclass
class SilAnalysis:
    token:str; root:str=''; pos:str='unknown'; number:str='singular'; case:str='absolutive'; tense:str=''; person:str='3'; person_number:str='sg'; voice:str='active'; register:str='ordinary'; derivation:str=''; ru:str=''; confidence:int=0; modifier:str=''

class Translator:
    def __init__(self):
        self.lex={}; self.lex_ci={}
        with LEX.open(encoding='utf-8') as f:
            for r in csv.DictReader(f,delimiter='\t'):
                e=Lex(r['root'],r['russian'],r['pos'],r['synonym_of'],r['register'],[x for x in r['aliases'].split('|') if x])
                self.lex[e.root]=e; self.lex_ci[e.root.lower()]=e
        # Grammar examples use ty even if the dictionary export omitted a standalone row.
        if 'ty' not in self.lex_ci:
            e=Lex('ty','идти','verb','','standard',['идти']); self.lex['ty']=e; self.lex_ci['ty']=e

        # Load semantic aliases first. They canonicalize older morphology rows
        # (e.g. смотреть -> jyr rather than an obsolete broad alias of vel).
        self.alias_cyr=defaultdict(list); self.alias_lat=defaultdict(list); self.alias_stem=defaultdict(list); self.alias_lat_stem=defaultdict(list)
        with ALIASES.open(encoding='utf-8') as f:
            for r in csv.DictReader(f,delimiter='\t'):
                rf=RuForm(r['alias'],r['latin'],r['root'],r['pos'],'sg','verb:infinitive:3:active' if r['pos']=='verb' else 'nom',r['alias'],int(r['priority']))
                self.alias_cyr[norm_ru(r['alias'])].append(rf)
                if r['latin']: self.alias_lat[r['latin'].lower()].append(rf)
                if r['stem']: self.alias_stem[r['stem']].append(rf)
                if r['latin_stem']: self.alias_lat_stem[r['latin_stem'].lower()].append(rf)
        for d in (self.alias_cyr,self.alias_lat,self.alias_stem,self.alias_lat_stem):
            for vals in d.values(): vals.sort(key=lambda x:x.priority)

        self.forms_cyr=defaultdict(list); self.forms_lat=defaultdict(list)
        self.out_forms=defaultdict(list)
        with FORMS.open(encoding='utf-8') as f:
            for r in csv.DictReader(f,delimiter='\t'):
                rf=RuForm(r['form'],r.get('latin',''),r['root'],r['pos'],r['number'],r['morph'],r['lemma'],int(r['priority']))
                sem=[x for x in self.alias_cyr.get(norm_ru(rf.lemma),[]) if x.pos==rf.pos]
                if sem: rf.root=sem[0].root
                self.forms_cyr[norm_ru(rf.form)].append(rf)
                if rf.latin:self.forms_lat[rf.latin.lower()].append(rf)
                self.out_forms[(rf.root,rf.pos,rf.number,rf.morph)].append(rf)
        for d in (self.forms_cyr,self.forms_lat,self.out_forms):
            for vals in d.values(): vals.sort(key=lambda x:(x.priority, 1 if self.lex_ci.get(x.root.lower(),Lex('','','','','',[])).register=='colloquial' else 0, len(x.lemma)))

    def choose(self,vals,prefer=None):
        if not vals:return None
        if prefer:
            p=[x for x in vals if x.pos==prefer]
            if p:return p[0]
        return vals[0]

    def resolve_ru(self,token,prefer=None):
        n=norm_ru(token)
        # Grammar pronouns/interrogatives outrank dictionary aliases. This is
        # necessary because the lexicon also contains ordinary nouns whose
        # Russian glosses happen to be "я", "ты", "он", etc.
        pron={}
        def pr(form,root,num,morph,lemma):
            return RuForm(form,form,root,'pronoun',num,morph,lemma,-20)
        for form,morph in [('я','nom'),('меня','acc'),('мне','dat'),('мной','ins'),('мною','ins')]:
            pron[form]=pr(form,'cə','sg',morph,'я')
        for form,morph in [('ты','nom'),('тебя','acc'),('тебе','dat'),('тобой','ins'),('тобою','ins')]:
            pron[form]=pr(form,'pavil','sg',morph,'ты')
        for form,morph in [('он','nom'),('его','acc'),('ему','dat'),('им','ins')]:
            pron[form]=pr(form,'hon','sg',morph,'он')
        for form,morph in [('она','nom'),('ее','acc'),('её','acc'),('ей','dat'),('ею','ins')]:
            pron[form]=pr(form,'dat','sg',morph,'она')
        pron['оно']=pr('оно','sysæg','sg','nom','оно')
        for form,morph in [('мы','nom'),('нас','acc'),('нам','dat'),('нами','ins')]:
            pron[form]=pr(form,'mæśærel','pl',morph,'мы')
        for form,morph in [('вы','nom'),('вас','acc'),('вам','dat'),('вами','ins')]:
            pron[form]=pr(form,'uneź','pl',morph,'вы')
        for form,morph in [('они','nom'),('их','acc'),('ими','ins')]:
            pron[form]=pr(form,'ynən','pl',morph,'они')
        if n in pron:return pron[n]

        special={
          'кто':RuForm('кто','kto','emøg','pronoun','sg','nom','кто',-10),
          'кого':RuForm('кого','kogo','emøg','pronoun','sg','acc','кто',-10),
          'кому':RuForm('кому','komu','emøg','pronoun','sg','dat','кто',-10),
          'кем':RuForm('кем','kem','emøg','pronoun','sg','ins','кто',-10),
          'что':RuForm('что','chto','hit','pronoun','sg','nom','что',-10),
          'чего':RuForm('чего','chego','hit','pronoun','sg','gen','что',-10),
          'чему':RuForm('чему','chemu','hit','pronoun','sg','dat','что',-10),
          'чем':RuForm('чем','chem','hit','pronoun','sg','ins','что',-10),
        }
        if n in special:return special[n]
        x=self.choose(self.forms_cyr.get(n),prefer)
        if x:return x
        x=self.choose(self.alias_cyr.get(n),prefer)
        if x:return x
        x=self.choose(self.alias_stem.get(russian_stem(n)),prefer)
        if x:return x
        low=token.lower()
        x=self.choose(self.forms_lat.get(low),prefer)
        if x:return x
        x=self.choose(self.alias_lat.get(low),prefer)
        if x:return x
        x=self.choose(self.alias_lat_stem.get(latin_stem(low)),prefer)
        return x

    def semantic_phrase_exists(self,s):
        return bool(self.alias_cyr.get(norm_ru(s)) or self.alias_lat.get(s.lower()))

    def merge_phrases(self,toks):
        out=[]; i=0
        while i<len(toks):
            if not is_word(toks[i]): out.append(toks[i]);i+=1;continue
            best=None;bn=1
            # Only join word-only phrases; punctuation is a hard boundary.
            for n in range(min(6,len(toks)-i),1,-1):
                q=toks[i:i+n]
                if not all(is_word(x) for x in q):continue
                s=' '.join(q)
                if self.semantic_phrase_exists(s):best=s;bn=n;break
            if best is not None:out.append(best);i+=bn
            else:out.append(toks[i]);i+=1
        return out

    def translate(self,text,direction='auto'):
        if direction=='auto':
            if CYR_RE.search(text): direction='ru2sil'
            else:
                sil=0;ru=0
                for tok in TOKEN_RE.findall(text):
                    if not is_word(tok):continue
                    a=self.analyze_sil(tok)
                    if a.confidence>=90:sil+=3
                    elif a.confidence>=75:sil+=1
                    if SIL_SPECIAL_RE.search(tok):sil+=2
                    if self.resolve_ru(tok):ru+=2
                    elif looks_latin_russian(tok):ru+=1
                direction='ru2sil' if ru>sil else 'sil2ru'
        return self.ru_to_sil(text) if direction=='ru2sil' else self.sil_to_ru(text)

    # ---------------- Sil'mir analysis ----------------
    def lexget(self,root):return self.lex_ci.get(root.lower())

    def deredup_root(self,r):
        for k in range(1,min(4,len(r)//2+1)):
            p=r[:k]
            if r.startswith(p+p):
                cand=p+r[2*k:]
                e=self.lexget(cand)
                if e and e.pos=='verb': return e.root
        return None

    def analyze_sil(self,token):
        low=token.lower()
        if low in PRON:return SilAnalysis(token,low,'pronoun',ru=PRON[low][0],confidence=100)
        if low in FUNCTION_SIL:return SilAnalysis(token,low,'particle',ru=FUNCTION_SIL[low],confidence=100)
        if low in IRREG_SIL:return SilAnalysis(token,low,'irregular',ru=IRREG_SIL[low],confidence=100)

        # Productive prefixes are peeled before the core analysis.
        for pref,mod in [('hy','intensive'),('sa','toward'),('ez','away')]:
            if low.startswith(pref) and len(low)>len(pref)+1:
                inner=self.analyze_sil(token[len(pref):])
                if inner.confidence>=75:
                    inner.token=token;inner.modifier=mod;inner.confidence+=2;return inner
        if low.startswith('na') and len(low)>4:
            inner=self.analyze_sil(token[2:])
            if inner.confidence>=75 and inner.pos in ('adjective','derived'):
                inner.token=token;inner.modifier='negated';inner.confidence+=2;return inner

        cand=[]
        cases=[('absolutive','')]+CASE_SUFFIXES
        nums=[('singular','')]+NUMBER_SUFFIXES
        for cname,cs in cases:
            if cs and not low.endswith(cs):continue
            s1=low[:-len(cs)] if cs else low
            for nname,ns in nums:
                if ns and not s1.endswith(ns):continue
                raw=s1[:-len(ns)] if ns else s1
                e=self.lexget(raw)
                if e and e.pos in ('noun','pronoun','numeral'):
                    cand.append(SilAnalysis(token,e.root,e.pos,nname,cname,ru=e.ru,confidence=84+(5 if cs else 0)+(4 if ns else 0)+(4 if e.register=='standard' else 0)))

        for nname,ns in nums:
            if ns and not low.endswith(ns):continue
            raw=low[:-len(ns)] if ns else low
            e=self.lexget(raw)
            if e and e.pos=='adjective':cand.append(SilAnalysis(token,e.root,'adjective',nname,ru=e.ru,confidence=88+(4 if ns else 0)))

        # Finite verbs.
        for reg,rs in [('ordinary','s'),('respectful','l')]:
            if not low.endswith(rs):continue
            body=low[:-1]
            for voice,vs in [('active',''),('passive','s'),('reflexive','m')]:
                for ps,pers,pnum in PERSON_PATTERNS:
                    tail=vs+ps
                    if tail and not body.endswith(tail):continue
                    b2=body[:-len(tail)] if tail else body
                    if len(b2)<2 or b2[-1] not in VOWEL_TENSE:continue
                    tense=VOWEL_TENSE[b2[-1]]; raw=b2[:-1]
                    e=self.lexget(raw); habitual=False
                    if e is None:
                        rr=self.deredup_root(raw)
                        if rr: e=self.lexget(rr);habitual=True
                    if e and e.pos=='verb':
                        cand.append(SilAnalysis(token,e.root,'verb',tense=tense,person=pers,person_number=pnum,voice=voice,register=reg,ru=e.ru,confidence=96 if voice=='active' else 86,modifier='habitual' if habitual else ''))

        for suf,kind in [('øjm','imperative_reflexive'),('øj','imperative'),('suk','passive_participle'),('uk','participle'),('eb','gerund')]:
            if low.endswith(suf):
                e=self.lexget(low[:-len(suf)])
                if e and e.pos=='verb':cand.append(SilAnalysis(token,e.root,'verb',derivation=kind,ru=e.ru,confidence=94))

        for suf,kind in [('yvm','superlative'),('yvs','excessive'),('yv','comparative'),('yś','attenuative'),('of','adverb'),('yb','adjective_from_noun'),('yn','agent'),('yl','action_result'),('ic','diminutive'),('uz','augmentative'),('av','collective'),('t','ordinal')]:
            if low.endswith(suf):
                e=self.lexget(low[:-len(suf)])
                if e:cand.append(SilAnalysis(token,e.root,'derived',derivation=kind,ru=e.ru,confidence=78))

        e=self.lexget(low)
        if e:cand.append(SilAnalysis(token,e.root,e.pos,ru=e.ru,confidence=90 if e.register=='standard' else 84))
        if not cand:return SilAnalysis(token,low,'unknown',ru=token,confidence=0)
        cand.sort(key=lambda a:(a.confidence,len(a.root)),reverse=True)
        return cand[0]

    def build_noun(self,root,number='singular',case='absolutive'):
        ns={'singular':'','dual':'et','plural':'in'}.get(number,'')
        cs=dict(CASE_SUFFIXES).get(case,'')
        return root+ns+cs

    def build_verb(self,root,tense='present',person='3',number='sg',voice='active',register='ordinary'):
        return root+TENSE_VOWEL.get(tense,'i')+{'active':'','passive':'s','reflexive':'m'}.get(voice,'')+PERSON_SUFFIX.get((person,number),'')+('l' if register=='respectful' else 's')

    def first_syllable(self,root):
        for i,ch in enumerate(root):
            if ch in VOWELS:return root[:i+1]
        return root[:1]

    # ---------------- Russian generation ----------------
    def output_form(self,root,pos,number,morph):
        vals=self.out_forms.get((root,pos,number,morph),[])
        return vals[0].form if vals else None

    def canonical_lemma(self,root):
        e=self.lexget(root)
        if not e:return transliterate_sil_to_ru(root)
        vals=[re.sub(r'\s*\([^)]*\).*','',x).strip() for x in e.aliases if x.strip()]
        vals=[x for x in vals if re.fullmatch(r'[А-Яа-яЁё -]+',x)]
        return vals[0] if vals else re.sub(r'\s*\([^)]*\).*','',e.ru).strip()

    def ru_noun(self,a):
        case_to_morph={'absolutive':'nom','ergative':'nom','accusative':'acc','dative':'dat','genitive':'gen','instrumental':'ins','locative':'loc','ablative':'gen','directional':'dat'}
        morph=case_to_morph.get(a.case,'nom')
        if a.number=='dual':
            nn='sg' if a.case in ('absolutive','ergative','accusative') else 'pl'
            mm='gen' if a.case in ('absolutive','ergative','accusative') else morph
            noun=self.output_form(a.root,'noun',nn,mm) or self.canonical_lemma(a.root)
            return DUAL_RU.get(a.case,'два')+' '+noun
        num='pl' if a.number=='plural' else 'sg'
        return self.output_form(a.root,'noun',num,morph) or self.canonical_lemma(a.root)

    def ru_pronoun(self,root,case):
        if root not in PRON:return self.canonical_lemma(root)
        a=PRON[root]
        if case in ('dative','directional'):return a[2]
        if case=='instrumental':return a[3]
        if case in ('accusative','genitive','ablative'):return a[1]
        return a[0]

    def ru_verb(self,a):
        if a.derivation.startswith('imperative'):
            voice='reflexive' if a.derivation.endswith('reflexive') else 'active'
            v=self.output_form(a.root,'verb','sg',f'verb:imperative:2:{voice}')
            return v or self.canonical_lemma(a.root)
        if a.derivation=='gerund':return self.canonical_lemma(a.root)+' (деепр.)'
        if a.derivation in ('participle','passive_participle'):return self.canonical_lemma(a.root)+' (прич.)'
        base_morph=f'verb:{a.tense or "present"}:{a.person}'
        morph=base_morph+':'+a.voice
        # Original curated rows omit an explicit active-voice field and should win when present.
        v=self.output_form(a.root,'verb',a.person_number,base_morph) if a.voice=='active' else None
        if v is None:v=self.output_form(a.root,'verb',a.person_number,morph)
        if v is None and a.voice!='active':
            v=self.output_form(a.root,'verb',a.person_number,f'verb:{a.tense or "present"}:{a.person}:active')
            if v: v=v+('ся' if not v.endswith(('ю','у')) else 'сь')
        if v is None and a.tense=='future':
            aux={('1','sg'):'буду',('2','sg'):'будешь',('3','sg'):'будет',('1','pl'):'будем',('2','pl'):'будете',('3','pl'):'будут'}[(a.person,a.person_number)]
            inf=self.output_form(a.root,'verb','sg','verb:infinitive:3:active') or self.canonical_lemma(a.root)
            v=aux+' '+inf
        if v is None:
            v=self.output_form(a.root,'verb',a.person_number,f'verb:{a.tense or "present"}:{a.person}') or self.output_form(a.root,'verb',a.person_number,f'verb:{a.tense or "present"}:{a.person}:active') or self.canonical_lemma(a.root)
        if a.modifier=='habitual':v='обычно '+v
        elif a.modifier=='intensive':v='пристально '+v
        elif a.modifier=='toward' and a.root=='ty':
            # Preserve person/tense while expressing the documented motion prefix naturally.
            if a.tense=='present' and a.person=='3' and a.person_number=='sg':v='подходит'
            else:v='к '+v
        elif a.modifier=='away' and a.root=='ty':
            if a.tense=='present' and a.person=='3' and a.person_number=='sg':v='уходит'
            else:v='от '+v
        return v

    def sil_word_to_ru(self,a):
        if a.pos=='unknown':return transliterate_sil_to_ru(a.token)
        if a.pos=='irregular':return a.ru
        if a.pos=='particle':return a.ru
        if a.pos=='pronoun':
            s=self.ru_pronoun(a.root,a.case)
            if a.case=='directional':return 'к '+s
            if a.case=='ablative':return 'от '+s
            return s
        if a.pos in ('noun','numeral'):
            s=self.ru_noun(a)
            if a.case=='locative':s='в '+s
            elif a.case=='ablative':s='из '+s
            elif a.case=='directional':s='к '+s
            return s
        if a.pos=='adjective':
            s=self.canonical_lemma(a.root)
            if a.number=='plural':
                if s.endswith(('ый','ой')):s=s[:-2]+'ые'
                elif s.endswith('ий'):s=s[:-2]+'ие'
            if a.modifier=='intensive':s='очень '+s
            if a.modifier=='negated':s='не'+s
            return s
        if a.pos=='verb':return self.ru_verb(a)
        if a.pos=='derived':
            l=self.canonical_lemma(a.root);d=a.derivation
            if d=='diminutive':return 'маленький '+l
            if d=='augmentative':return 'огромный '+l
            if d=='collective':return 'скопление '+l
            if d=='adjective_from_noun':return 'относящийся к '+l
            if d=='agent':return 'тот, кто '+l
            if d=='action_result':return 'результат '+l
            if d=='comparative':return 'более '+l
            if d=='superlative':return 'самый '+l
            if d=='attenuative':return 'слегка '+l
            if d=='excessive':return 'слишком '+l
            if d=='adverb':
                if l.endswith(('ый','ий','ой')):return l[:-2]+'о'
                return l+' образом'
            if d=='ordinal':return 'порядковый '+l
        return self.canonical_lemma(a.root)

    # ---------------- Sil'mir -> Russian ----------------
    def sil_to_ru(self,text):
        toks=TOKEN_RE.findall(text)
        question=False
        if toks and toks[0].lower()=='li':
            question=True;toks=toks[1:]
        analyses=[self.analyze_sil(t) if is_word(t) else None for t in toks]
        out=[];i=0
        while i<len(toks):
            a=analyses[i]
            if a is None:out.append(toks[i]);i+=1;continue
            # If this is clearly Russian transliteration embedded in Sil'mir, normalize it to Russian.
            if a.confidence<70:
                rf=self.resolve_ru(toks[i])
                if rf:
                    out.append(rf.lemma);i+=1;continue
            if a.case=='genitive' and i+1<len(toks) and analyses[i+1] and analyses[i+1].pos in ('noun','pronoun'):
                out.append(self.sil_word_to_ru(analyses[i+1]));out.append(self.sil_word_to_ru(a));i+=2;continue
            out.append(self.sil_word_to_ru(a));i+=1
        s=self.join(out)
        if question and not s.endswith('?'):s+='?'
        return self.cap_like(text,s)

    # ---------------- Russian parsing helpers ----------------
    def prev_word(self,toks,i):
        for j in range(i-1,-1,-1):
            if is_word(toks[j]):return j,toks[j]
            if toks[j] in '.!?;':break
        return None,''
    def next_word(self,toks,i,limit=5):
        n=0
        for j in range(i+1,len(toks)):
            if toks[j] in '.!?;':break
            if is_word(toks[j]):
                n+=1
                if n<=limit:return j,toks[j]
        return None,''

    def prep_before(self,toks,i):
        # Allow adjective/numeral between preposition and noun.
        seen=0
        for j in range(i-1,max(-1,i-4),-1):
            if not is_word(toks[j]):
                if toks[j] in ',.;!?':break
                continue
            w=norm_ru(toks[j]);seen+=1
            if w in PREP_SCAN:return w
            if seen>=2:break
        return ''

    def case_from_prep(self,prep,rf,motion=False):
        if prep in PREP_DIRECTIONAL:return 'directional'
        if prep in PREP_ABLATIVE:return 'ablative'
        if prep in PREP_LOCATIVE:return 'locative'
        if prep in PREP_LOC_DIR:
            if rf and rf.morph=='acc':return 'directional'
            if rf and rf.morph=='loc':return 'locative'
            return 'directional' if motion else 'locative'
        return None

    def person_for_subject(self,rf):
        if rf is None:return ('3','sg')
        if rf.root=='cə':return ('1','sg')
        if rf.root=='pavil':return ('2','sg')
        if rf.root=='mæśærel':return ('1','pl')
        if rf.root=='uneź':return ('2','pl')
        if rf.root=='ynən':return ('3','pl')
        return ('3','pl' if rf.number=='pl' else 'sg')

    def relation_maps(self,toks,info,extra_verbs=None):
        extra_verbs=extra_verbs or set()
        verbs=[i for i,x in enumerate(info) if x and x.pos=='verb' and x.morph.startswith('verb:') and (':infinitive:' not in x.morph or i in extra_verbs)]
        subj={};obj={};last_subj=None
        for vi in verbs:
            s=None
            # Prefer nominative unprepositioned NP to the left.
            for i in range(vi-1,-1,-1):
                if toks[i] in '.!?;':break
                if info[i] and info[i].pos=='verb':break
                if info[i] and info[i].pos in ('noun','pronoun') and not self.prep_before(toks,i):
                    if info[i].morph in ('nom','') or info[i].pos=='pronoun':s=i;break
                    if s is None:s=i
            if s is None:s=last_subj
            if s is not None:last_subj=s
            subj[vi]=s

            candidates=[]
            for i in range(vi+1,len(toks)):
                if toks[i] in '.!?;':break
                if info[i] and info[i].pos=='verb' and ':infinitive:' not in info[i].morph:break
                if info[i] and info[i].pos in ('noun','pronoun') and not self.prep_before(toks,i):
                    candidates.append(i)
            o=None
            for i in candidates:
                if info[i].morph=='acc':o=i;break
            if o is None and candidates:o=candidates[0]
            # Topic-fronted Russian object: "камень я вижу". If the nearest
            # pre-verbal subject is a pronoun, an earlier NP can be the object.
            if o is None and s is not None and info[s] and info[s].pos=='pronoun':
                for i in range(s-1,-1,-1):
                    if toks[i] in '.!?;':break
                    if info[i] and info[i].pos in ('noun','pronoun') and not self.prep_before(toks,i):
                        o=i;break
            obj[vi]=o
        return verbs,subj,obj

    def governing_verb(self,i,verbs):
        if not verbs:return None
        # nearest verb, bias to a preceding verb for objects/destinations
        return min(verbs,key=lambda v:(abs(v-i),0 if v<i else 1))

    def adjective_head(self,i,info,toks):
        for j in range(i+1,min(len(info),i+6)):
            if toks[j] in ',.;!?':break
            if info[j] and info[j].pos in ('noun','pronoun'):return j
        return None

    def numeral_heads(self,toks,info):
        numhead={}; headnum={}
        for i,t in enumerate(toks):
            nt=norm_ru(t)
            if nt not in NUM2|NUM_PLURAL:continue
            for j in range(i+1,min(len(toks),i+5)):
                if toks[j] in ',.;!?':break
                if info[j] and info[j].pos in ('noun','pronoun'):
                    numhead[i]=j;headnum[j]=nt;break
        return numhead,headnum

    def marker_targets(self,toks,info):
        neg=set();habit=set();intense=set();degree={};skip=set()
        for i,t in enumerate(toks):
            w=norm_ru(t)
            if w=='не':
                for j in range(i+1,min(len(toks),i+4)):
                    if info[j] and info[j].pos in ('verb','adjective'):
                        neg.add(j);skip.add(i);break
            elif w in HABITUAL:
                for j in range(i+1,min(len(toks),i+5)):
                    if info[j] and info[j].pos=='verb':habit.add(j);skip.add(i);break
            elif w in INTENSIFIERS|VERB_INTENSIFIERS:
                for j in range(i+1,min(len(toks),i+4)):
                    if info[j] and info[j].pos in ('verb','adjective'):intense.add(j);skip.add(i);break
            elif w in DEGREES:
                for j in range(i+1,min(len(toks),i+4)):
                    if info[j] and info[j].pos=='adjective':degree[j]=DEGREES[w];skip.add(i);break
        return neg,habit,intense,degree,skip

    def translate_ru_clause(self,text):
        raw=TOKEN_RE.findall(text)
        toks=self.merge_phrases(raw)
        info=[]
        for t in toks:
            if not is_word(t) and ' ' not in t:info.append(None);continue
            rf=self.resolve_ru(t)
            if rf is None:
                # Mixed-language input: preserve a confidently recognized Sil'mir token.
                a=self.analyze_sil(t)
                if a.confidence>=85:info.append(None)
                else:info.append(None)
            else:info.append(rf)

        # Analytic future: auxiliary + infinitive/verb. Resolve it before role parsing so the lexical infinitive can govern an object.
        future_override={};aux_skip=set()
        for i,t in enumerate(toks):
            w=norm_ru(t)
            if w not in FUTURE_AUX:continue
            for j in range(i+1,min(len(toks),i+6)):
                if info[j] and info[j].pos=='verb':
                    future_override[j]=FUTURE_AUX[w];aux_skip.add(i);break

        verbs,subj,obj=self.relation_maps(toks,info,extra_verbs=set(future_override))
        numhead,headnum=self.numeral_heads(toks,info)
        neg,habit,intense,degree,marker_skip=self.marker_targets(toks,info)

        out=[];skip=set(marker_skip)|aux_skip
        # "бы" is encoded by vizøs when paired with если.
        for i,t in enumerate(toks):
            if norm_ru(t)=='бы':skip.add(i)
            elif norm_ru(t)=='за':
                # Russian verbs of observation govern "за + instrumental";
                # Sil'mir encodes the watched entity as the verb object.
                for j in range(i-1,max(-1,i-5),-1):
                    if info[j] and info[j].pos=='verb':
                        if norm_ru(info[j].lemma) in VIEW_LEMMAS:skip.add(i)
                        break

        wh=any(norm_ru(t) in WH_RU for t in toks if is_word(t))
        question=text.rstrip().endswith('?')

        for i,tok in enumerate(toks):
            if i in skip:continue
            rf=info[i]
            if rf is None and not is_word(tok):out.append(tok);continue
            nt=norm_ru(tok)
            if nt in SUPPORTED_PREPS:continue
            if nt=='если':
                out.append('vizøs');continue
            if nt=='бы':continue
            if nt=='не':
                # orphan negation when no analyzable target was found
                out.append('na');continue

            if rf is None:
                a=self.analyze_sil(tok)
                if a.confidence>=85:out.append(tok)
                else:out.append(transliterate_ru_to_sil(tok))
                continue

            if rf.pos=='aux':continue
            if rf.pos=='particle':
                if rf.root=='na':out.append('na')
                elif rf.root=='vizøs':out.append('vizøs')
                continue

            # Numerals. Two is encoded by dual on the noun; 3+ remains a lexical numeral and forces plural.
            if nt in NUM2:continue
            if rf.pos=='numeral':out.append(rf.root);continue

            if rf.pos=='verb':
                parts=rf.morph.split(':')
                kind=parts[1] if len(parts)>1 else 'present'
                voice=parts[3] if len(parts)>3 else ('reflexive' if rf.lemma.endswith(('ся','сь')) else 'active')
                if kind=='infinitive' and i not in future_override:
                    # Bare Russian infinitive has no dedicated finite Sil'mir form. Keep root as lexical fallback.
                    out.append(rf.root);continue
                if kind=='imperative':
                    root=rf.root
                    if i in intense:root='hy'+root
                    out.append(root+('øjm' if voice=='reflexive' else 'øj'));continue
                tense='future' if i in future_override else kind
                person=parts[2] if len(parts)>2 else '3';num=rf.number if rf.number in ('sg','pl') else 'sg'
                vi=self.governing_verb(i,verbs)
                si=subj.get(i) if i in subj else (subj.get(vi) if vi is not None else None)
                if si is not None:person,num=self.person_for_subject(info[si])
                if i in future_override:person,num=future_override[i]
                root=rf.root
                lem=norm_ru(rf.lemma)
                if lem in TOWARD_LEMMAS and root=='ty':root='sa'+root
                elif lem in AWAY_LEMMAS and root=='ty':root='ez'+root
                if i in habit:root=self.first_syllable(root)+root
                if i in intense:root='hy'+root
                if i in neg:out.append('na')
                out.append(self.build_verb(root,tense,person,num,voice,'ordinary'));continue

            if rf.pos in ('noun','pronoun'):
                number='plural' if rf.number=='pl' else 'singular'
                kase='absolutive'
                numeral=headnum.get(i)
                if numeral in NUM2:number='dual'
                elif numeral in NUM_PLURAL:number='plural'
                vi=self.governing_verb(i,verbs);motion=False
                if vi is not None and info[vi]:motion=norm_ru(info[vi].lemma) in MOTION_LEMMAS
                prep=self.prep_before(toks,i)
                gov_lemma=norm_ru(info[vi].lemma) if vi is not None and info[vi] else ''
                if prep in ('на','в','во','за') and gov_lemma in VIEW_LEMMAS:
                    pc='accusative'
                else:
                    pc=self.case_from_prep(prep,rf,motion)
                if pc:kase=pc
                elif vi is not None and obj.get(vi)==i:kase='accusative'
                elif vi is not None and subj.get(vi)==i and obj.get(vi) is not None and rf.pos=='noun':kase='ergative'
                elif numeral in NUM2:
                    # Russian numeral government must not make "два камня" genitive in Sil'mir.
                    kase={'двух':'genitive','двум':'dative','двумя':'instrumental'}.get(numeral,'absolutive')
                elif numeral in NUM_PLURAL:
                    # Ignore Russian genitive forced by 3+; syntactic role already won above.
                    kase='absolutive'
                else:
                    kase={'gen':'genitive','dat':'dative','acc':'accusative','ins':'instrumental','loc':'locative'}.get(rf.morph,kase)
                if nt in WH_RU:
                    out.append(rf.root)
                elif rf.pos=='pronoun':
                    if rf.root in ('hit','emøg','najem'): out.append(rf.root)
                    else: out.append(rf.root+('ən' if kase=='accusative' else ''))
                else:out.append(self.build_noun(rf.root,number,kase))
                continue

            if rf.pos=='adverb':
                out.append(rf.root+'of');continue

            if rf.pos=='adjective':
                head=self.adjective_head(i,info,toks);number='singular'
                if head is not None:
                    n=headnum.get(head)
                    if n in NUM2:number='dual'
                    elif n in NUM_PLURAL or info[head].number=='pl':number='plural'
                root=rf.root
                if i in neg:root='na'+root
                if i in intense:root='hy'+root
                suf={'comparative':'yv','superlative':'yvm','attenuative':'yś','excessive':'yvs'}.get(degree.get(i),'')
                out.append(root+{'singular':'','dual':'et','plural':'in'}[number]+suf);continue

            out.append(rf.root)

        s=self.join(out)
        if question and not wh and not s.lower().startswith('li '):s='li '+s
        return s

    # ---------------- Russian -> Sil'mir ----------------
    def ru_to_sil(self,text):
        # Parse each sentence/semicolon-delimited clause independently so roles do not bleed across sentences.
        parts=re.split(r'([.!?;]+)',text)
        out=[]
        for i,p in enumerate(parts):
            if not p:continue
            if re.fullmatch(r'[.!?;]+',p):
                # Question mark was already considered by the preceding chunk.
                if p.startswith('?'):
                    if out and not out[-1].endswith('?'):out[-1]+='?'
                    if len(p)>1:out.append(p[1:])
                else:out.append(p)
            else:
                # Give the clause knowledge of a following question mark.
                nxt=parts[i+1] if i+1<len(parts) else ''
                q=p+('?' if nxt.startswith('?') else '')
                tr=self.translate_ru_clause(q).rstrip('?')
                stripped=p.lstrip()
                if stripped[:1].isupper() and tr:tr=tr[0].upper()+tr[1:]
                out.append(tr)
        s=''.join(out)
        s=re.sub(r'([.!?;])(?=[A-Za-zÆØƏŚŹæøəśźА-Яа-яЁё])',r'\1 ',s)
        return self.cap_like(text,s)

    @staticmethod
    def join(parts):
        s=''
        for p in parts:
            if not p:continue
            if re.fullmatch(r'[,.;:!?\)\]]',p):s=s.rstrip()+p
            elif re.fullmatch(r'[\(\[]',p):s+=(' ' if s and not s.endswith(' ') else '')+p
            else:s+=(' ' if s and not s.endswith((' ','(','[')) else '')+p
        return s.strip()
    @staticmethod
    def cap_like(src,out):
        if src[:1].isupper() and out:return out[0].upper()+out[1:]
        return out

if __name__=='__main__':
    tr=Translator()
    if len(sys.argv)>=3:print(tr.translate(' '.join(sys.argv[2:]),sys.argv[1]))
    else:
        tests=[
          'Я вижу камень','Я видел камень','Я увижу камень','Я буду видеть камень',
          'Я иду в дом','Я стою в доме','Я вышел из дома','Я вижу два камня','Я вижу три камня',
          'Мы видели твердые камни','Я не вижу камень','Очень твердый камень','Самый твердый камень',
          'Ты видишь камень?','Кто видит камень?','Если бы я видел камень',
          'cə velims kairən','cufəś daris lumən','domov','domyf','domiź','satyis','eztyis',
          'Kurbaniaən nelegitimnoe gosudarstvo'
        ]
        for x in tests:print(x,'=>',tr.translate(x))
