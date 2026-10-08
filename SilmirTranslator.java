package juloo.keyboard2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline MEGA Sil'mir <-> Russian translator.
 *
 * The Sil'mir side is grammar-driven. The Russian side uses an expanded
 * morphology/semantic index plus sentence-level heuristics. No network calls.
 */
public final class SilmirTranslator
{
  private static final Pattern TOKEN = Pattern.compile(
      "[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+|\\d+|[^\\w\\s]");
  private static final Pattern CYR = Pattern.compile("[А-Яа-яЁё]");
  private static final Pattern SIL_SPECIAL = Pattern.compile("[ÆØƏŚŹæøəśź]");

  static final class Lex
  {
    String root, ru, pos, synonym, register;
    String[] aliases;
  }

  static final class RuForm
  {
    String form, latin, root, pos, number, morph, lemma;
    int priority;
  }

  static final class A
  {
    String token, root="", pos="unknown", number="singular", kase="absolutive";
    String tense="", person="3", personNumber="sg", voice="active", register="ordinary";
    String derivation="", ru="", modifier="";
    int confidence=0;
    A(String t){token=t;}
  }

  private final Map<String,Lex> lex = new HashMap<String,Lex>();
  private final Map<String,Lex> lexCi = new HashMap<String,Lex>();
  private final Map<String,List<RuForm>> formsCyr = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> formsLat = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> aliasCyr = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> aliasLat = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> aliasStem = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> aliasLatStem = new HashMap<String,List<RuForm>>();
  private final Map<String,List<RuForm>> outForms = new HashMap<String,List<RuForm>>();

  private static final Map<String,String[]> PRON = new HashMap<String,String[]>();
  private static final Map<String,String> SIL_FUNCTION = new HashMap<String,String>();
  private static final Map<String,String[]> FUTURE_AUX = new HashMap<String,String[]>();
  private static final Set<String> PREP_LOC_DIR = set("в","во","на");
  private static final Set<String> PREP_DIRECTIONAL = set("к","ко");
  private static final Set<String> PREP_ABLATIVE = set("из","от","ото","с","со");
  private static final Set<String> PREP_LOCATIVE = set("при");
  private static final Set<String> SUPPORTED_PREPS = union(PREP_LOC_DIR,PREP_DIRECTIONAL,PREP_ABLATIVE,PREP_LOCATIVE);
  private static final Set<String> PREP_SCAN = union(SUPPORTED_PREPS,set("за"));
  private static final Set<String> VIEW = set("смотреть","глядеть","наблюдать","посмотреть","взглянуть","следить");
  private static final Set<String> MOTION = set("идти","ходить","ехать","бежать","лететь","плыть","двигаться","направляться","приходить","уходить","входить","выходить","подходить","отходить","пойти","прийти","уйти","подойти","отойти","выйти","войти");
  private static final Set<String> TOWARD = set("подходить","подойти","приходить","прийти","приближаться","войти");
  private static final Set<String> AWAY = set("уходить","уйти","отходить","отойти","удаляться","выйти");
  private static final Set<String> HABITUAL = set("обычно","регулярно","постоянно");
  private static final Set<String> INTENSIFIERS = set("очень","крайне","весьма","пристально");
  private static final Set<String> WH = set("кто","кого","кому","кем","что","чего","чему","чем","где","куда","откуда","когда","почему","зачем","как","какой","какая","какое","какие","сколько");
  private static final Set<String> NUM2 = set("два","две","двух","двум","двумя");
  private static final Set<String> NUMPL = set("три","четыре","пять","шесть","семь","восемь","девять","десять");
  private static final Set<Character> VOWELS = chars("aæeəioøuy");

  static
  {
    PRON.put("cə",new String[]{"я","меня","мне","мной"});
    PRON.put("pavil",new String[]{"ты","тебя","тебе","тобой"});
    PRON.put("hon",new String[]{"он","его","ему","им"});
    PRON.put("dat",new String[]{"она","ее","ей","ею"});
    PRON.put("sysæg",new String[]{"оно","его","ему","им"});
    PRON.put("mæśærel",new String[]{"мы","нас","нам","нами"});
    PRON.put("uneź",new String[]{"вы","вас","вам","вами"});
    PRON.put("ynən",new String[]{"они","их","им","ими"});
    SIL_FUNCTION.put("na","не"); SIL_FUNCTION.put("vizøs","если бы"); SIL_FUNCTION.put("li","");
    SIL_FUNCTION.put("emøg","кто"); SIL_FUNCTION.put("hit","что"); SIL_FUNCTION.put("najem","никто");
    FUTURE_AUX.put("буду",new String[]{"1","sg"}); FUTURE_AUX.put("будешь",new String[]{"2","sg"}); FUTURE_AUX.put("будет",new String[]{"3","sg"});
    FUTURE_AUX.put("будем",new String[]{"1","pl"}); FUTURE_AUX.put("будете",new String[]{"2","pl"}); FUTURE_AUX.put("будут",new String[]{"3","pl"});
  }

  public SilmirTranslator(InputStream lexiconTsv, InputStream russianFormsTsv, InputStream semanticAliasesTsv) throws IOException
  {
    loadLexicon(lexiconTsv);
    loadAliases(semanticAliasesTsv);
    loadForms(russianFormsTsv);
    if (!lexCi.containsKey("ty"))
    {
      Lex e=new Lex(); e.root="ty";e.ru="идти";e.pos="verb";e.synonym="";e.register="standard";e.aliases=new String[]{"идти"};
      lex.put(e.root,e);lexCi.put("ty",e);
    }
  }

  private static Set<String> set(String... xs)
  {
    HashSet<String> s=new HashSet<String>(); Collections.addAll(s,xs); return s;
  }
  @SafeVarargs
  private static Set<String> union(Set<String>... sets)
  {
    HashSet<String> r=new HashSet<String>(); for(Set<String>s:sets)r.addAll(s); return r;
  }
  private static Set<Character> chars(String s)
  {
    HashSet<Character> r=new HashSet<Character>(); for(int i=0;i<s.length();i++)r.add(s.charAt(i)); return r;
  }

  private void loadLexicon(InputStream in) throws IOException
  {
    BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
    br.readLine();String line;
    while((line=br.readLine())!=null)
    {
      String[] c=line.split("\\t",-1); if(c.length<7)continue;
      Lex e=new Lex();e.root=c[0];e.ru=c[1];e.pos=c[2];e.synonym=c[4];e.register=c[5];e.aliases=c[6].isEmpty()?new String[]{e.ru}:c[6].split("\\|");
      lex.put(e.root,e);lexCi.put(e.root.toLowerCase(Locale.ROOT),e);
    }
    br.close();
  }

  private static void addList(Map<String,List<RuForm>> map,String key,RuForm f)
  {
    List<RuForm> l=map.get(key); if(l==null){l=new ArrayList<RuForm>();map.put(key,l);} l.add(f);
  }
  private static void sortMaps(Map<String,List<RuForm>> map)
  {
    for(List<RuForm> l:map.values())Collections.sort(l,new Comparator<RuForm>(){public int compare(RuForm a,RuForm b){return a.priority-b.priority;}});
  }
  private void loadForms(InputStream in) throws IOException
  {
    BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
    String header=br.readLine();boolean mega=header!=null&&header.contains("latin");String line;
    while((line=br.readLine())!=null)
    {
      String[] c=line.split("\\t",-1); if((mega&&c.length<8)||(!mega&&c.length<7))continue;
      RuForm f=new RuForm();
      if(mega){f.form=c[0];f.latin=c[1];f.root=c[2];f.pos=c[3];f.number=c[4];f.morph=c[5];f.lemma=c[6];try{f.priority=Integer.parseInt(c[7]);}catch(Exception e){f.priority=999;}}
      else{f.form=c[0];f.latin="";f.root=c[1];f.pos=c[2];f.number=c[3];f.morph=c[4];f.lemma=c[5];try{f.priority=Integer.parseInt(c[6]);}catch(Exception e){f.priority=999;}}
      RuForm sem=choose(aliasCyr.get(normRu(f.lemma)),f.pos);
      if(sem!=null)f.root=sem.root;
      addList(formsCyr,normRu(f.form),f); if(f.latin!=null&&!f.latin.isEmpty())addList(formsLat,f.latin.toLowerCase(Locale.ROOT),f);
      addList(outForms,outKey(f.root,f.pos,f.number,f.morph),f);
    }
    br.close();sortMaps(formsCyr);sortMaps(formsLat);sortMaps(outForms);
  }
  private void loadAliases(InputStream in) throws IOException
  {
    BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));br.readLine();String line;
    while((line=br.readLine())!=null)
    {
      String[] c=line.split("\\t",-1);if(c.length<8)continue;
      RuForm f=new RuForm();f.form=c[0];f.latin=c[6];f.root=c[1];f.pos=c[2];f.number="sg";f.lemma=c[0];
      f.morph=f.pos.equals("verb")?"verb:infinitive:3:active":"nom";try{f.priority=Integer.parseInt(c[3]);}catch(Exception e){f.priority=50;}
      addList(aliasCyr,normRu(c[0]),f);if(!c[6].isEmpty())addList(aliasLat,c[6].toLowerCase(Locale.ROOT),f);
      if(!c[5].isEmpty())addList(aliasStem,c[5],f);if(!c[7].isEmpty())addList(aliasLatStem,c[7].toLowerCase(Locale.ROOT),f);
    }
    br.close();sortMaps(aliasCyr);sortMaps(aliasLat);sortMaps(aliasStem);sortMaps(aliasLatStem);
  }

  private RuForm choose(List<RuForm> vals,String prefer)
  {
    if(vals==null||vals.isEmpty())return null;
    if(prefer!=null)for(RuForm x:vals)if(prefer.equals(x.pos))return x;
    return vals.get(0);
  }
  private RuForm specialPronoun(String n)
  {
    String root=null,morph="nom",lemma=n;
    if(n.equals("я")||n.equals("меня")||n.equals("мне")||n.equals("мной")||n.equals("мною"))
    { root="cə"; lemma="я"; if(n.equals("меня"))morph="acc"; else if(n.equals("мне"))morph="dat"; else if(n.equals("мной")||n.equals("мною"))morph="ins"; }
    else if(n.equals("ты")||n.equals("тебя")||n.equals("тебе")||n.equals("тобой")||n.equals("тобою"))
    { root="pavil"; lemma="ты"; if(n.equals("тебя"))morph="acc"; else if(n.equals("тебе"))morph="dat"; else if(n.equals("тобой")||n.equals("тобою"))morph="ins"; }
    else if(n.equals("он")||n.equals("его")||n.equals("ему")||n.equals("им"))
    { root="hon"; lemma="он"; if(n.equals("его"))morph="acc"; else if(n.equals("ему"))morph="dat"; else if(n.equals("им"))morph="ins"; }
    else if(n.equals("она")||n.equals("ее")||n.equals("её")||n.equals("ей")||n.equals("ею"))
    { root="dat"; lemma="она"; if(n.equals("ее")||n.equals("её"))morph="acc"; else if(n.equals("ей"))morph="dat"; else if(n.equals("ею"))morph="ins"; }
    else if(n.equals("оно")) { root="sysæg"; lemma="оно"; }
    else if(n.equals("мы")||n.equals("нас")||n.equals("нам")||n.equals("нами"))
    { root="mæśærel"; lemma="мы"; if(n.equals("нас"))morph="acc"; else if(n.equals("нам"))morph="dat"; else if(n.equals("нами"))morph="ins"; }
    else if(n.equals("вы")||n.equals("вас")||n.equals("вам")||n.equals("вами"))
    { root="uneź"; lemma="вы"; if(n.equals("вас"))morph="acc"; else if(n.equals("вам"))morph="dat"; else if(n.equals("вами"))morph="ins"; }
    else if(n.equals("они")||n.equals("их")||n.equals("ими"))
    { root="ynən"; lemma="они"; if(n.equals("их"))morph="acc"; else if(n.equals("ими"))morph="ins"; }
    if(root==null)return null;
    RuForm f=new RuForm();f.form=n;f.latin=n;f.root=root;f.pos="pronoun";f.number=(root.equals("mæśærel")||root.equals("uneź")||root.equals("ynən"))?"pl":"sg";f.morph=morph;f.lemma=lemma;f.priority=-20;return f;
  }

  private RuForm specialQuestion(String n)
  {
    String root=null,morph="nom",lemma=n;
    if(n.equals("кто")||n.equals("кого")||n.equals("кому")||n.equals("кем")){root="emøg";lemma="кто";}
    else if(n.equals("что")||n.equals("чего")||n.equals("чему")||n.equals("чем")){root="hit";lemma="что";}
    if(root==null)return null;
    if(n.equals("кого")||n.equals("чего"))morph="acc";else if(n.equals("кому")||n.equals("чему"))morph="dat";else if(n.equals("кем")||n.equals("чем"))morph="ins";
    RuForm f=new RuForm();f.form=n;f.latin=n;f.root=root;f.pos="pronoun";f.number="sg";f.morph=morph;f.lemma=lemma;f.priority=-10;return f;
  }
  private RuForm resolveRussian(String token){return resolveRussian(token,null);}
  private RuForm resolveRussian(String token,String prefer)
  {
    String n=normRu(token);RuForm gp=specialPronoun(n);if(gp!=null)return gp;RuForm sp=specialQuestion(n);if(sp!=null)return sp;
    RuForm f=choose(formsCyr.get(n),prefer);if(f!=null)return f;
    f=choose(aliasCyr.get(n),prefer);if(f!=null)return f;
    f=choose(aliasStem.get(russianStem(n)),prefer);if(f!=null)return f;
    String l=token.toLowerCase(Locale.ROOT);
    f=choose(formsLat.get(l),prefer);if(f!=null)return f;
    f=choose(aliasLat.get(l),prefer);if(f!=null)return f;
    return choose(aliasLatStem.get(latinStem(l)),prefer);
  }

  public String translate(String text)
  {
    if(CYR.matcher(text).find())return russianToSilmir(text);
    int sil=0,ru=0;for(String tok:tokens(text))if(isWord(tok))
    {
      A a=analyzeSil(tok);if(a.confidence>=90)sil+=3;else if(a.confidence>=75)sil++;
      if(SIL_SPECIAL.matcher(tok).find())sil+=2;
      if(resolveRussian(tok)!=null)ru+=2;else if(looksLatinRussian(tok))ru++;
    }
    return ru>sil?russianToSilmir(text):silmirToRussian(text);
  }

  // ---------------- Sil'mir morphology ----------------
  private Lex lexget(String r){return lexCi.get(r.toLowerCase(Locale.ROOT));}
  private String deredupRoot(String r)
  {
    for(int k=1;k<=3&&2*k<=r.length();k++)
    {
      String p=r.substring(0,k);if(r.startsWith(p+p))
      {
        String cand=p+r.substring(2*k);Lex e=lexget(cand);if(e!=null&&"verb".equals(e.pos))return e.root;
      }
    }
    return null;
  }

  A analyzeSil(String token)
  {
    String low=token.toLowerCase(Locale.ROOT);
    if(PRON.containsKey(low)){A a=new A(token);a.root=low;a.pos="pronoun";a.ru=PRON.get(low)[0];a.confidence=100;return a;}
    if(SIL_FUNCTION.containsKey(low)){A a=new A(token);a.root=low;a.pos="particle";a.ru=SIL_FUNCTION.get(low);a.confidence=100;return a;}
    if(low.equals("tyt")||low.equals("hremem")||low.equals("lavi")){A a=new A(token);a.root=low;a.pos="irregular";a.ru=low.equals("tyt")?"иди":low.equals("hremem")?"умойся":"в унитазе";a.confidence=100;return a;}

    String[][] pref={{"hy","intensive"},{"sa","toward"},{"ez","away"}};
    for(String[] q:pref)if(low.startsWith(q[0])&&low.length()>q[0].length()+1)
    {
      A a=analyzeSil(token.substring(q[0].length()));if(a.confidence>=75){a.token=token;a.modifier=q[1];a.confidence+=2;return a;}
    }
    if(low.startsWith("na")&&low.length()>4)
    {
      A a=analyzeSil(token.substring(2));if(a.confidence>=75&&(a.pos.equals("adjective")||a.pos.equals("derived"))){a.token=token;a.modifier="negated";a.confidence+=2;return a;}
    }

    ArrayList<A> cand=new ArrayList<A>();
    String[][] cases={{"absolutive",""},{"ergative","əś"},{"accusative","ən"},{"directional","iź"},{"dative","uv"},{"genitive","om"},{"instrumental","yr"},{"locative","ov"},{"ablative","yf"}};
    String[][] nums={{"singular",""},{"dual","et"},{"plural","in"}};
    for(String[] cs:cases)
    {
      if(!cs[1].isEmpty()&&!low.endsWith(cs[1]))continue;String s1=cs[1].isEmpty()?low:low.substring(0,low.length()-cs[1].length());
      for(String[] ns:nums)
      {
        if(!ns[1].isEmpty()&&!s1.endsWith(ns[1]))continue;String rr=ns[1].isEmpty()?s1:s1.substring(0,s1.length()-ns[1].length());Lex e=lexget(rr);
        if(e!=null&&(e.pos.equals("noun")||e.pos.equals("pronoun")||e.pos.equals("numeral"))){A a=new A(token);a.root=e.root;a.pos=e.pos;a.number=ns[0];a.kase=cs[0];a.ru=e.ru;a.confidence=84+(!cs[1].isEmpty()?5:0)+(!ns[1].isEmpty()?4:0)+(e.register.equals("standard")?4:0);cand.add(a);}
      }
    }
    for(String[] ns:nums)
    {
      if(!ns[1].isEmpty()&&!low.endsWith(ns[1]))continue;String rr=ns[1].isEmpty()?low:low.substring(0,low.length()-ns[1].length());Lex e=lexget(rr);
      if(e!=null&&e.pos.equals("adjective")){A a=new A(token);a.root=e.root;a.pos="adjective";a.number=ns[0];a.ru=e.ru;a.confidence=88+(!ns[1].isEmpty()?4:0);cand.add(a);}
    }

    String[][] persons={{"mn","1","pl"},{"śn","2","pl"},{"m","1","sg"},{"ś","2","sg"},{"n","3","pl"},{"","3","sg"}};
    String[][] voices={{"","active"},{"s","passive"},{"m","reflexive"}};
    for(String[] rg:new String[][]{{"s","ordinary"},{"l","respectful"}})if(low.endsWith(rg[0]))
    {
      String body=low.substring(0,low.length()-1);
      for(String[] v:voices)for(String[] p:persons)
      {
        String tail=v[0]+p[0];if(!tail.isEmpty()&&!body.endsWith(tail))continue;String b2=tail.isEmpty()?body:body.substring(0,body.length()-tail.length());if(b2.length()<2)continue;
        char tv=b2.charAt(b2.length()-1);String tense=tv=='a'?"past":tv=='i'?"present":tv=='u'?"future":"";if(tense.isEmpty())continue;
        String rr=b2.substring(0,b2.length()-1);Lex e=lexget(rr);boolean hab=false;if(e==null){String dr=deredupRoot(rr);if(dr!=null){e=lexget(dr);hab=true;}}
        if(e!=null&&e.pos.equals("verb")){A a=new A(token);a.root=e.root;a.pos="verb";a.tense=tense;a.person=p[1];a.personNumber=p[2];a.voice=v[1];a.register=rg[1];a.ru=e.ru;a.confidence=v[1].equals("active")?96:86;a.modifier=hab?"habitual":"";cand.add(a);}
      }
    }

    String[][] der={{"øjm","imperative_reflexive"},{"øj","imperative"},{"suk","passive_participle"},{"uk","participle"},{"eb","gerund"},{"yvm","superlative"},{"yvs","excessive"},{"yv","comparative"},{"yś","attenuative"},{"of","adverb"},{"yb","adjective_from_noun"},{"yn","agent"},{"yl","action_result"},{"ic","diminutive"},{"uz","augmentative"},{"av","collective"},{"t","ordinal"}};
    for(String[] d:der)if(low.endsWith(d[0]))
    {
      Lex e=lexget(low.substring(0,low.length()-d[0].length()));if(e!=null){A a=new A(token);a.root=e.root;a.pos=e.pos.equals("verb")?"verb":"derived";a.derivation=d[1];a.ru=e.ru;a.confidence=e.pos.equals("verb")?94:78;cand.add(a);}
    }
    Lex ex=lexget(low);if(ex!=null){A a=new A(token);a.root=ex.root;a.pos=ex.pos;a.ru=ex.ru;a.confidence=ex.register.equals("standard")?90:84;cand.add(a);}
    if(cand.isEmpty()){A a=new A(token);a.root=low;a.ru=token;return a;}
    Collections.sort(cand,new Comparator<A>(){public int compare(A a,A b){int d=b.confidence-a.confidence;return d!=0?d:b.root.length()-a.root.length();}});return cand.get(0);
  }

  private String buildNoun(String root,String number,String kase)
  {
    String ns=number.equals("dual")?"et":number.equals("plural")?"in":"";
    String cs=kase.equals("ergative")?"əś":kase.equals("accusative")?"ən":kase.equals("dative")?"uv":kase.equals("genitive")?"om":kase.equals("instrumental")?"yr":kase.equals("locative")?"ov":kase.equals("ablative")?"yf":kase.equals("directional")?"iź":"";
    return root+ns+cs;
  }
  private String buildVerb(String root,String tense,String person,String number,String voice,String register)
  {
    String tv=tense.equals("past")?"a":tense.equals("future")?"u":"i";String v=voice.equals("passive")?"s":voice.equals("reflexive")?"m":"";
    String p=person.equals("1")?(number.equals("pl")?"mn":"m"):person.equals("2")?(number.equals("pl")?"śn":"ś"):(number.equals("pl")?"n":"");
    return root+tv+v+p+(register.equals("respectful")?"l":"s");
  }
  private String firstSyllable(String r){for(int i=0;i<r.length();i++)if(VOWELS.contains(r.charAt(i)))return r.substring(0,i+1);return r.substring(0,Math.min(1,r.length()));}

  // ---------------- Russian output ----------------
  private static String outKey(String root,String pos,String num,String morph){return root+"\u0001"+pos+"\u0001"+num+"\u0001"+morph;}
  private String outputForm(String root,String pos,String num,String morph){List<RuForm> l=outForms.get(outKey(root,pos,num,morph));return l==null||l.isEmpty()?null:l.get(0).form;}
  private String canonicalLemma(String root)
  {
    Lex e=lexget(root);if(e==null)return transliterateSilToRu(root);
    for(String a:e.aliases){String q=a.replaceAll("\\s*\\([^)]*\\).*","").trim();if(q.matches("[А-Яа-яЁё -]+")&&!q.isEmpty())return q;}
    return e.ru.replaceAll("\\s*\\([^)]*\\).*","").trim();
  }
  private String ruPronoun(String root,String kase)
  {
    String[] a=PRON.get(root);if(a==null)return canonicalLemma(root);if(kase.equals("dative")||kase.equals("directional"))return a[2];if(kase.equals("instrumental"))return a[3];if(kase.equals("accusative")||kase.equals("genitive")||kase.equals("ablative"))return a[1];return a[0];
  }
  private String ruNoun(A a)
  {
    String morph=a.kase.equals("genitive")||a.kase.equals("ablative")?"gen":a.kase.equals("dative")||a.kase.equals("directional")?"dat":a.kase.equals("accusative")?"acc":a.kase.equals("instrumental")?"ins":a.kase.equals("locative")?"loc":"nom";
    if(a.number.equals("dual"))
    {
      String nn=(a.kase.equals("absolutive")||a.kase.equals("ergative")||a.kase.equals("accusative"))?"sg":"pl";String mm=(a.kase.equals("absolutive")||a.kase.equals("ergative")||a.kase.equals("accusative"))?"gen":morph;
      String noun=outputForm(a.root,"noun",nn,mm);if(noun==null)noun=canonicalLemma(a.root);
      String num=a.kase.equals("genitive")||a.kase.equals("locative")||a.kase.equals("ablative")?"двух":a.kase.equals("dative")||a.kase.equals("directional")?"двум":a.kase.equals("instrumental")?"двумя":"два";return num+" "+noun;
    }
    String n=a.number.equals("plural")?"pl":"sg";String q=outputForm(a.root,"noun",n,morph);return q==null?canonicalLemma(a.root):q;
  }
  private String ruVerb(A a)
  {
    if(a.derivation.startsWith("imperative"))
    {
      String voice=a.derivation.endsWith("reflexive")?"reflexive":"active";String v=outputForm(a.root,"verb","sg","verb:imperative:2:"+voice);return v==null?canonicalLemma(a.root):v;
    }
    if(a.derivation.equals("gerund"))return canonicalLemma(a.root)+" (деепр.)";
    if(a.derivation.equals("participle")||a.derivation.equals("passive_participle"))return canonicalLemma(a.root)+" (прич.)";
    String t=a.tense.isEmpty()?"present":a.tense;String base="verb:"+t+":"+a.person;String v=null;
    if(a.voice.equals("active"))v=outputForm(a.root,"verb",a.personNumber,base);
    if(v==null)v=outputForm(a.root,"verb",a.personNumber,base+":"+a.voice);
    if(v==null&&a.voice.equals("active"))v=outputForm(a.root,"verb",a.personNumber,base+":active");
    if(v==null&&a.tense.equals("future"))
    {
      String aux=a.person.equals("1")?(a.personNumber.equals("pl")?"будем":"буду"):a.person.equals("2")?(a.personNumber.equals("pl")?"будете":"будешь"):(a.personNumber.equals("pl")?"будут":"будет");
      String inf=outputForm(a.root,"verb","sg","verb:infinitive:3:active");if(inf==null)inf=canonicalLemma(a.root);v=aux+" "+inf;
    }
    if(v==null)v=canonicalLemma(a.root);
    if(!a.voice.equals("active")&&!v.endsWith("ся")&&!v.endsWith("сь"))v+=v.endsWith("ю")||v.endsWith("у")?"сь":"ся";
    if(a.modifier.equals("habitual"))v="обычно "+v;else if(a.modifier.equals("intensive"))v="пристально "+v;
    else if(a.modifier.equals("toward")&&a.root.equals("ty"))v=(a.tense.equals("present")&&a.person.equals("3")&&a.personNumber.equals("sg"))?"подходит":"к "+v;
    else if(a.modifier.equals("away")&&a.root.equals("ty"))v=(a.tense.equals("present")&&a.person.equals("3")&&a.personNumber.equals("sg"))?"уходит":"от "+v;
    return v;
  }
  private String silWordToRussian(A a)
  {
    if(a.pos.equals("unknown"))return transliterateSilToRu(a.token);if(a.pos.equals("irregular")||a.pos.equals("particle"))return a.ru;
    if(a.pos.equals("pronoun")){String s=ruPronoun(a.root,a.kase);if(a.kase.equals("directional"))return "к "+s;if(a.kase.equals("ablative"))return "от "+s;return s;}
    if(a.pos.equals("noun")||a.pos.equals("numeral")){String s=ruNoun(a);if(a.kase.equals("locative"))s="в "+s;else if(a.kase.equals("ablative"))s="из "+s;else if(a.kase.equals("directional"))s="к "+s;return s;}
    if(a.pos.equals("adjective")){String s=canonicalLemma(a.root);if(a.number.equals("plural")){if(s.endsWith("ый")||s.endsWith("ой"))s=s.substring(0,s.length()-2)+"ые";else if(s.endsWith("ий"))s=s.substring(0,s.length()-2)+"ие";}if(a.modifier.equals("intensive"))s="очень "+s;if(a.modifier.equals("negated"))s="не"+s;return s;}
    if(a.pos.equals("verb"))return ruVerb(a);
    if(a.pos.equals("derived")){String l=canonicalLemma(a.root),d=a.derivation;if(d.equals("diminutive"))return "маленький "+l;if(d.equals("augmentative"))return "огромный "+l;if(d.equals("collective"))return "скопление "+l;if(d.equals("adjective_from_noun"))return "относящийся к "+l;if(d.equals("agent"))return "тот, кто "+l;if(d.equals("action_result"))return "результат "+l;if(d.equals("comparative"))return "более "+l;if(d.equals("superlative"))return "самый "+l;if(d.equals("attenuative"))return "слегка "+l;if(d.equals("excessive"))return "слишком "+l;if(d.equals("adverb"))return (l.endsWith("ый")||l.endsWith("ий")||l.endsWith("ой"))?l.substring(0,l.length()-2)+"о":l+" образом";if(d.equals("ordinal"))return "порядковый "+l;}
    return canonicalLemma(a.root);
  }

  public String silmirToRussian(String text)
  {
    List<String> t=tokens(text);boolean question=false;if(!t.isEmpty()&&t.get(0).equalsIgnoreCase("li")){question=true;t.remove(0);}List<A>a=new ArrayList<A>();for(String s:t)a.add(isWord(s)?analyzeSil(s):null);
    ArrayList<String> out=new ArrayList<String>();for(int i=0;i<t.size();)
    {
      A x=a.get(i);if(x==null){out.add(t.get(i));i++;continue;}
      if(x.confidence<70){RuForm rf=resolveRussian(t.get(i));if(rf!=null){out.add(rf.lemma);i++;continue;}}
      if(x.kase.equals("genitive")&&i+1<a.size()&&a.get(i+1)!=null&&(a.get(i+1).pos.equals("noun")||a.get(i+1).pos.equals("pronoun"))){out.add(silWordToRussian(a.get(i+1)));out.add(silWordToRussian(x));i+=2;continue;}
      out.add(silWordToRussian(x));i++;
    }
    String s=join(out);if(question&&!s.endsWith("?"))s+="?";return capitalizeLike(text,s);
  }

  // ---------------- Russian sentence analysis ----------------
  private static boolean wordEq(String s,String q){return normRu(s).equals(q);}
  private String prepBefore(List<String>t,int i)
  {
    int seen=0;for(int j=i-1;j>=0&&j>=i-4;j--){String q=t.get(j);if(!isWord(q)){if(q.matches("[,.;!?]"))break;continue;}String w=normRu(q);seen++;if(PREP_SCAN.contains(w))return w;if(seen>=2)break;}return "";
  }
  private String caseFromPrep(String prep,RuForm f,boolean motion)
  {
    if(PREP_DIRECTIONAL.contains(prep))return "directional";if(PREP_ABLATIVE.contains(prep))return "ablative";if(PREP_LOCATIVE.contains(prep))return "locative";
    if(PREP_LOC_DIR.contains(prep)){if(f!=null&&f.morph.equals("acc"))return "directional";if(f!=null&&f.morph.equals("loc"))return "locative";return motion?"directional":"locative";}return null;
  }
  private String[] personForSubject(RuForm f)
  {
    if(f==null)return new String[]{"3","sg"};if(f.root.equals("cə"))return new String[]{"1","sg"};if(f.root.equals("pavil"))return new String[]{"2","sg"};if(f.root.equals("mæśærel"))return new String[]{"1","pl"};if(f.root.equals("uneź"))return new String[]{"2","pl"};if(f.root.equals("ynən"))return new String[]{"3","pl"};return new String[]{"3",f.number.equals("pl")?"pl":"sg"};
  }
  private List<String> mergeRussianPhrases(List<String> raw)
  {
    ArrayList<String>out=new ArrayList<String>();for(int i=0;i<raw.size();)
    {
      if(!isWord(raw.get(i))){out.add(raw.get(i++));continue;}String best=null;int bn=1;
      for(int n=Math.min(6,raw.size()-i);n>=2;n--){boolean ok=true;StringBuilder b=new StringBuilder();for(int j=0;j<n;j++){String q=raw.get(i+j);if(!isWord(q)){ok=false;break;}if(j>0)b.append(' ');b.append(q);}if(!ok)continue;String q=b.toString();if(aliasCyr.containsKey(normRu(q))||aliasLat.containsKey(q.toLowerCase(Locale.ROOT))){best=q;bn=n;break;}}
      if(best!=null){out.add(best);i+=bn;}else out.add(raw.get(i++));
    }return out;
  }

  private String translateRussianClause(String text)
  {
    List<String> t=mergeRussianPhrases(tokens(text));int n=t.size();ArrayList<RuForm> info=new ArrayList<RuForm>();for(String q:t)info.add((isWord(q)||q.indexOf(' ')>=0)?resolveRussian(q):null);

    // Analytic future lexical verb indices and auxiliary features.
    Map<Integer,String[]> future=new HashMap<Integer,String[]>();HashSet<Integer> skip=new HashSet<Integer>();
    for(int i=0;i<n;i++)if(isWord(t.get(i))&&FUTURE_AUX.containsKey(normRu(t.get(i))))for(int j=i+1;j<n&&j<i+6;j++)if(info.get(j)!=null&&info.get(j).pos.equals("verb")){future.put(j,FUTURE_AUX.get(normRu(t.get(i))));skip.add(i);break;}

    ArrayList<Integer> verbs=new ArrayList<Integer>();for(int i=0;i<n;i++){RuForm x=info.get(i);if(x!=null&&x.pos.equals("verb")&&x.morph.startsWith("verb:")&&(!x.morph.contains(":infinitive:")||future.containsKey(i)))verbs.add(i);}
    Map<Integer,Integer> subj=new HashMap<Integer,Integer>(),obj=new HashMap<Integer,Integer>();Integer lastSubj=null;
    for(int vi:verbs)
    {
      Integer s=null;for(int i=vi-1;i>=0;i--){if(t.get(i).matches("[.!?;]"))break;RuForm x=info.get(i);if(x!=null&&x.pos.equals("verb"))break;if(x!=null&&(x.pos.equals("noun")||x.pos.equals("pronoun"))&&prepBefore(t,i).isEmpty()){if(x.morph.equals("nom")||x.pos.equals("pronoun")){s=i;break;}if(s==null)s=i;}}
      if(s==null)s=lastSubj;if(s!=null)lastSubj=s;subj.put(vi,s);
      ArrayList<Integer> cs=new ArrayList<Integer>();for(int i=vi+1;i<n;i++){if(t.get(i).matches("[.!?;]"))break;RuForm x=info.get(i);if(x!=null&&x.pos.equals("verb")&&!x.morph.contains(":infinitive:"))break;if(x!=null&&(x.pos.equals("noun")||x.pos.equals("pronoun"))&&prepBefore(t,i).isEmpty())cs.add(i);}Integer o=null;for(int i:cs)if(info.get(i).morph.equals("acc")){o=i;break;}if(o==null&&!cs.isEmpty())o=cs.get(0);
      if(o==null&&s!=null&&info.get(s)!=null&&info.get(s).pos.equals("pronoun"))for(int i=s-1;i>=0;i--){if(t.get(i).matches("[.!?;]"))break;RuForm x=info.get(i);if(x!=null&&(x.pos.equals("noun")||x.pos.equals("pronoun"))&&prepBefore(t,i).isEmpty()){o=i;break;}}
      obj.put(vi,o);
    }

    Map<Integer,String> headNum=new HashMap<Integer,String>();HashSet<Integer> numeralSkip=new HashSet<Integer>();
    for(int i=0;i<n;i++)if(isWord(t.get(i))&&(NUM2.contains(normRu(t.get(i)))||NUMPL.contains(normRu(t.get(i)))))for(int j=i+1;j<n&&j<i+5;j++){RuForm x=info.get(j);if(x!=null&&(x.pos.equals("noun")||x.pos.equals("pronoun"))){headNum.put(j,normRu(t.get(i)));if(NUM2.contains(normRu(t.get(i))))numeralSkip.add(i);break;}}

    HashSet<Integer> neg=new HashSet<Integer>(),habit=new HashSet<Integer>(),intense=new HashSet<Integer>();Map<Integer,String> degree=new HashMap<Integer,String>();
    for(int i=0;i<n;i++)if(isWord(t.get(i)))
    {
      String w=normRu(t.get(i));if(w.equals("не")){for(int j=i+1;j<n&&j<i+4;j++){RuForm x=info.get(j);if(x!=null&&(x.pos.equals("verb")||x.pos.equals("adjective"))){neg.add(j);skip.add(i);break;}}}
      else if(HABITUAL.contains(w)){for(int j=i+1;j<n&&j<i+5;j++){RuForm x=info.get(j);if(x!=null&&x.pos.equals("verb")){habit.add(j);skip.add(i);break;}}}
      else if(INTENSIFIERS.contains(w)){for(int j=i+1;j<n&&j<i+4;j++){RuForm x=info.get(j);if(x!=null&&(x.pos.equals("verb")||x.pos.equals("adjective"))){intense.add(j);skip.add(i);break;}}}
      else if(w.equals("более")||w.startsWith("сам")||w.equals("слегка")||w.equals("слишком")){String d=w.equals("более")?"comparative":w.startsWith("сам")?"superlative":w.equals("слегка")?"attenuative":"excessive";for(int j=i+1;j<n&&j<i+4;j++){RuForm x=info.get(j);if(x!=null&&x.pos.equals("adjective")){degree.put(j,d);skip.add(i);break;}}}
      if(w.equals("бы"))skip.add(i);
      if(w.equals("за"))
      {
        for(int j=i-1;j>=0&&j>=i-5;j--)
        {
          RuForm z=info.get(j);
          if(z!=null&&z.pos.equals("verb")){if(VIEW.contains(normRu(z.lemma)))skip.add(i);break;}
        }
      }
    }

    boolean question=text.trim().endsWith("?");boolean wh=false;for(String q:t)if(isWord(q)&&WH.contains(normRu(q)))wh=true;
    ArrayList<String> out=new ArrayList<String>();
    for(int i=0;i<n;i++)
    {
      if(skip.contains(i)||numeralSkip.contains(i))continue;String tok=t.get(i);RuForm f=info.get(i);if(f==null&&!isWord(tok)){out.add(tok);continue;}String nt=normRu(tok);
      if(SUPPORTED_PREPS.contains(nt))continue;if(nt.equals("если")){out.add("vizøs");continue;}if(nt.equals("бы"))continue;
      if(f==null){A sa=analyzeSil(tok);out.add(sa.confidence>=85?tok:transliterateRuToSil(tok));continue;}
      if(f.pos.equals("aux"))continue;if(f.pos.equals("particle")){if(f.root.equals("na"))out.add("na");else if(f.root.equals("vizøs"))out.add("vizøs");continue;}
      if(NUM2.contains(nt))continue;if(f.pos.equals("numeral")){out.add(f.root);continue;}

      if(f.pos.equals("verb"))
      {
        String[] mp=f.morph.split(":");String kind=mp.length>1?mp[1]:"present",person=mp.length>2?mp[2]:"3",voice=mp.length>3?mp[3]:(f.lemma.endsWith("ся")||f.lemma.endsWith("сь")?"reflexive":"active"),num=f.number.equals("pl")?"pl":"sg";
        if(kind.equals("infinitive")&&!future.containsKey(i)){out.add(f.root);continue;}
        if(kind.equals("imperative")){String root=f.root;if(intense.contains(i))root="hy"+root;out.add(root+(voice.equals("reflexive")?"øjm":"øj"));continue;}
        if(future.containsKey(i)){kind="future";person=future.get(i)[0];num=future.get(i)[1];}
        Integer si=subj.get(i);if(si!=null){String[] pn=personForSubject(info.get(si));person=pn[0];num=pn[1];}
        String root=f.root,lem=normRu(f.lemma);if(TOWARD.contains(lem)&&root.equals("ty"))root="sa"+root;else if(AWAY.contains(lem)&&root.equals("ty"))root="ez"+root;if(habit.contains(i))root=firstSyllable(root)+root;if(intense.contains(i))root="hy"+root;if(neg.contains(i))out.add("na");out.add(buildVerb(root,kind,person,num,voice,"ordinary"));continue;
      }
      if(f.pos.equals("noun")||f.pos.equals("pronoun"))
      {
        String number=f.number.equals("pl")?"plural":"singular",kase="absolutive",numeral=headNum.get(i);if(numeral!=null&&NUM2.contains(numeral))number="dual";else if(numeral!=null&&NUMPL.contains(numeral))number="plural";
        Integer gv=null;if(!verbs.isEmpty()){int best=999999;for(int v:verbs){int d=Math.abs(v-i);if(d<best){best=d;gv=v;}}}boolean motion=gv!=null&&info.get(gv)!=null&&MOTION.contains(normRu(info.get(gv).lemma));String pc=caseFromPrep(prepBefore(t,i),f,motion);
        String prep=prepBefore(t,i);String govLemma=(gv!=null&&info.get(gv)!=null)?normRu(info.get(gv).lemma):"";
        if((prep.equals("на")||prep.equals("в")||prep.equals("во")||prep.equals("за"))&&VIEW.contains(govLemma))pc="accusative";
        if(pc!=null)kase=pc;else if(gv!=null&&obj.get(gv)!=null&&obj.get(gv)==i)kase="accusative";else if(gv!=null&&subj.get(gv)!=null&&subj.get(gv)==i&&obj.get(gv)!=null&&f.pos.equals("noun"))kase="ergative";else if(numeral!=null&&NUM2.contains(numeral))kase=numeral.equals("двух")?"genitive":numeral.equals("двум")?"dative":numeral.equals("двумя")?"instrumental":"absolutive";else if(numeral!=null&&NUMPL.contains(numeral))kase="absolutive";else kase=f.morph.equals("gen")?"genitive":f.morph.equals("dat")?"dative":f.morph.equals("acc")?"accusative":f.morph.equals("ins")?"instrumental":f.morph.equals("loc")?"locative":kase;
        if(WH.contains(nt))out.add(f.root);
        else if(f.pos.equals("pronoun")){if(f.root.equals("hit")||f.root.equals("emøg")||f.root.equals("najem"))out.add(f.root);else out.add(f.root+(kase.equals("accusative")?"ən":""));}else out.add(buildNoun(f.root,number,kase));continue;
      }
      if(f.pos.equals("adverb")){out.add(f.root+"of");continue;}
      if(f.pos.equals("adjective"))
      {
        String number="singular";for(int j=i+1;j<n&&j<i+6;j++){RuForm h=info.get(j);if(h!=null&&(h.pos.equals("noun")||h.pos.equals("pronoun"))){String nu=headNum.get(j);if(nu!=null&&NUM2.contains(nu))number="dual";else if((nu!=null&&NUMPL.contains(nu))||h.number.equals("pl"))number="plural";break;}}
        String root=f.root;if(neg.contains(i))root="na"+root;if(intense.contains(i))root="hy"+root;String d=degree.get(i),suf=d==null?"":d.equals("comparative")?"yv":d.equals("superlative")?"yvm":d.equals("attenuative")?"yś":"yvs";out.add(root+(number.equals("dual")?"et":number.equals("plural")?"in":"")+suf);continue;
      }
      out.add(f.root);
    }
    String s=join(out);if(question&&!wh&&!s.toLowerCase(Locale.ROOT).startsWith("li "))s="li "+s;return s;
  }

  public String russianToSilmir(String text)
  {
    // Sentence punctuation is preserved. Each text chunk gets fresh role parsing.
    Matcher m=Pattern.compile("([^.!?;]+)([.!?;]*)").matcher(text);StringBuilder b=new StringBuilder();int end=0;
    while(m.find())
    {
      String body=m.group(1),pun=m.group(2);String q=body+(pun.contains("?")?"?":"");String tr=translateRussianClause(q);
      if(pun.contains("?")&&tr.endsWith("?"))tr=tr.substring(0,tr.length()-1);
      String bt=body.trim();if(!bt.isEmpty()&&Character.isUpperCase(bt.charAt(0))&&!tr.isEmpty())tr=Character.toUpperCase(tr.charAt(0))+tr.substring(1);
      if(b.length()>0&&b.charAt(b.length()-1)!=' '&&!tr.isEmpty())b.append(' ');
      b.append(tr).append(pun);end=m.end();
    }
    if(end<text.length())b.append(text.substring(end));return b.toString().trim();
  }

  // ---------------- utilities ----------------
  private static List<String> tokens(String s){ArrayList<String>r=new ArrayList<String>();Matcher m=TOKEN.matcher(s);while(m.find())r.add(m.group());return r;}
  private static boolean isWord(String s){return s!=null&&s.matches("[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+");}
  private static String normRu(String s){return s.toLowerCase(Locale.ROOT).replace('ё','е').trim();}
  private static String russianStem(String s)
  {
    s=normRu(s);if(s.indexOf(' ')>=0)return s;String[] sf={"иями","ями","ами","его","ого","ему","ому","ими","ыми","ешь","ишь","ете","ите","ую","юю","ей","ой","ий","ый","ая","яя","ое","ее","ые","ие","ых","их","ым","им","ом","ем","ах","ях","ам","ям","ов","ев","ть","ти","чь","ся","сь","ала","али","ила","или","ал","ил","ел","ела","ели","ет","ит","ут","ют","ат","ят","ы","и","а","я","у","ю","о","е","ь","й"};for(String x:sf)if(s.length()-x.length()>=3&&s.endsWith(x))return s.substring(0,s.length()-x.length());return s;
  }
  private static String latinStem(String s)
  {
    s=s.toLowerCase(Locale.ROOT);if(s.indexOf(' ')>=0)return s;String[] sf={"iyami","yami","ami","ogo","ego","emu","omu","imi","ymi","esh","ish","ete","ite","uyu","yuyu","aya","yaya","oe","ee","ye","ie","ykh","ikh","ym","im","om","em","akh","yakh","am","yam","ov","ev","ey","sya","ala","ali","ila","ili","al","il","et","it","ut","yut","at","yat","iy","yy","oy","ti","t","a","i","y","u","o","e"};for(String x:sf)if(s.length()-x.length()>=3&&s.endsWith(x))return s.substring(0,s.length()-x.length());return s;
  }
  private static boolean looksLatinRussian(String s){String x=s.toLowerCase(Locale.ROOT);return x.matches(".*(?:shch|zh|kh|ts|ch|sh|ya|yu|yo).*")||x.matches(".*(?:ogo|emu|ami|ymi|aya|oe|ie|stvo|nie|skiy|skaya|ovat|ivat|it|at|yat|ost|enie)$");}
  private static String transliterateRuToSil(String s)
  {
    StringBuilder b=new StringBuilder();String l=s.toLowerCase(Locale.ROOT);for(int i=0;i<l.length();i++){char c=l.charAt(i);switch(c){case 'а':b.append("a");break;case 'б':b.append("b");break;case 'в':b.append("v");break;case 'г':b.append("g");break;case 'д':b.append("d");break;case 'е':b.append("e");break;case 'ё':b.append("jo");break;case 'ж':b.append("ź");break;case 'з':b.append("z");break;case 'и':b.append("i");break;case 'й':b.append("j");break;case 'к':b.append("k");break;case 'л':b.append("l");break;case 'м':b.append("m");break;case 'н':b.append("n");break;case 'о':b.append("o");break;case 'п':b.append("p");break;case 'р':b.append("r");break;case 'с':b.append("s");break;case 'т':b.append("t");break;case 'у':b.append("u");break;case 'ф':b.append("f");break;case 'х':b.append("h");break;case 'ц':b.append("ts");break;case 'ч':b.append("tś");break;case 'ш':b.append("ś");break;case 'щ':b.append("śś");break;case 'ъ':break;case 'ы':b.append("y");break;case 'ь':break;case 'э':b.append("e");break;case 'ю':b.append("ju");break;case 'я':b.append("ja");break;default:if(Character.isLetterOrDigit(c))b.append(c);}}
    return b.toString();
  }
  private static String transliterateSilToRu(String s)
  {
    String x=s.toLowerCase(Locale.ROOT),out="";for(int i=0;i<x.length();){if(x.startsWith("śś",i)){out+="щ";i+=2;continue;}if(x.startsWith("tś",i)){out+="ч";i+=2;continue;}if(x.startsWith("ts",i)){out+="ц";i+=2;continue;}char c=x.charAt(i++);switch(c){case 'a':out+="а";break;case 'æ':out+="ай";break;case 'b':out+="б";break;case 'd':out+="д";break;case 'c':out+="к";break;case 'e':out+="е";break;case 'ə':out+="э";break;case 'f':out+="ф";break;case 'g':out+="г";break;case 'h':out+="х";break;case 'i':out+="и";break;case 'j':out+="й";break;case 'k':out+="к";break;case 'l':out+="л";break;case 'm':out+="м";break;case 'n':out+="н";break;case 'o':out+="о";break;case 'ø':out+="ой";break;case 'p':out+="п";break;case 'r':out+="р";break;case 's':out+="с";break;case 'ś':out+="ш";break;case 't':out+="т";break;case 'u':out+="у";break;case 'v':out+="в";break;case 'y':out+="ы";break;case 'z':out+="з";break;case 'ź':out+="ж";break;default:out+=c;}}return out;
  }
  private static String join(List<String> p)
  {
    StringBuilder b=new StringBuilder();for(String s:p){if(s==null||s.isEmpty())continue;if(s.matches("[,.;:!?\\)\\]]")){while(b.length()>0&&b.charAt(b.length()-1)==' ')b.deleteCharAt(b.length()-1);b.append(s);}else if(s.matches("[\\(\\[]")){if(b.length()>0&&b.charAt(b.length()-1)!=' ')b.append(' ');b.append(s);}else{if(b.length()>0&&b.charAt(b.length()-1)!=' '&&b.charAt(b.length()-1)!='('&&b.charAt(b.length()-1)!='[')b.append(' ');b.append(s);}}return b.toString().trim();
  }
  private static String capitalizeLike(String src,String out){if(src!=null&&!src.isEmpty()&&Character.isUpperCase(src.charAt(0))&&out!=null&&!out.isEmpty())return Character.toUpperCase(out.charAt(0))+out.substring(1);return out;}
}
