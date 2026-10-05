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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline rule-based Sil'mir <-> Russian translator.
 *
 * Vocabulary is stored as roots, not pre-expanded Sil'mir word forms. Sil'mir
 * morphology is analysed/generated from the language rules. Russian forms are
 * a separate pre-generated reverse index used only to recover Russian lemmas
 * and grammatical features.
 */
public final class SilmirTranslator
{
  static final Pattern TOKEN = Pattern.compile(
      "[A-Za-zÆØƏŚŹæøəśź]+(?:'[A-Za-zÆØƏŚŹæøəśź]+)?|[А-Яа-яЁё]+|\\d+|[^\\w\\s]");
  static final Pattern CYR = Pattern.compile("[А-Яа-яЁё]");
  static final Set<String> WORD_POS = set("noun", "pronoun");

  static final class Lex
  {
    String root, ru, pos, synonym, register;
    String[] aliases;
  }

  static final class RuForm
  {
    String form, root, pos, number, morph, lemma;
    int priority;
  }

  static final class A
  {
    String token, root = "", pos = "unknown", number = "singular", kase = "absolutive";
    String tense = "", person = "3", personNumber = "sg", voice = "active", register = "ordinary";
    String derivation = "", ru = "";
    int confidence = 0;
    A(String t) { token = t; }
  }

  private final Map<String, Lex> lex = new HashMap<String, Lex>();
  private final Map<String, RuForm> ruForms = new HashMap<String, RuForm>();
  private final Map<String, RuForm> semanticAliases = new HashMap<String, RuForm>();
  private final Map<String, RuForm> semanticStems = new HashMap<String, RuForm>();
  private final Map<String, RuForm> semanticLatin = new HashMap<String, RuForm>();
  private final Map<String, RuForm> semanticLatinStems = new HashMap<String, RuForm>();

  private static final Map<String, String> SIL_FUNCTION = new HashMap<String, String>();
  private static final Map<String, String[]> PRON = new HashMap<String, String[]>();
  private static final Map<String, String> RU_PREP = new HashMap<String, String>();
  private static final Map<String, String> DUAL_RU = new HashMap<String, String>();

  static
  {
    SIL_FUNCTION.put("na", "не"); SIL_FUNCTION.put("vizøs", "если"); SIL_FUNCTION.put("li", "ли");
    SIL_FUNCTION.put("emøg", "кто"); SIL_FUNCTION.put("hit", "что");
    PRON.put("cə", new String[]{"я","меня","мне","мной"});
    PRON.put("pavil", new String[]{"ты","тебя","тебе","тобой"});
    PRON.put("hon", new String[]{"он","его","ему","им"});
    PRON.put("dat", new String[]{"она","ее","ей","ею"});
    PRON.put("sysæg", new String[]{"оно","его","ему","им"});
    PRON.put("mæśærel", new String[]{"мы","нас","нам","нами"});
    PRON.put("uneź", new String[]{"вы","вас","вам","вами"});
    PRON.put("ynən", new String[]{"они","их","им","ими"});
    RU_PREP.put("в","locative"); RU_PREP.put("во","locative"); RU_PREP.put("на","locative");
    RU_PREP.put("к","directional"); RU_PREP.put("ко","directional");
    RU_PREP.put("из","ablative"); RU_PREP.put("от","ablative"); RU_PREP.put("ото","ablative");
    DUAL_RU.put("absolutive","два"); DUAL_RU.put("ergative","два"); DUAL_RU.put("accusative","два");
    DUAL_RU.put("genitive","двух"); DUAL_RU.put("dative","двум"); DUAL_RU.put("instrumental","двумя");
    DUAL_RU.put("locative","двух"); DUAL_RU.put("ablative","двух"); DUAL_RU.put("directional","двум");
  }

  public SilmirTranslator(InputStream lexiconTsv, InputStream russianFormsTsv, InputStream semanticAliasesTsv) throws IOException
  {
    loadLexicon(lexiconTsv);
    loadSemanticAliases(semanticAliasesTsv);
    loadRuForms(russianFormsTsv);
  }

  private void loadLexicon(InputStream in) throws IOException
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line = br.readLine(); // header
    while ((line = br.readLine()) != null)
    {
      String[] c = line.split("\\t", -1);
      if (c.length < 7) continue;
      Lex e = new Lex();
      e.root = c[0]; e.ru = c[1]; e.pos = c[2]; e.synonym = c[4]; e.register = c[5];
      e.aliases = c[6].isEmpty() ? new String[]{e.ru} : c[6].split("\\|");
      lex.put(e.root, e);
    }
    br.close();
  }

  private void loadRuForms(InputStream in) throws IOException
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line = br.readLine(); // header
    while ((line = br.readLine()) != null)
    {
      String[] c = line.split("\\t", -1);
      if (c.length < 7) continue;
      RuForm f = new RuForm();
      f.form = c[0]; f.root = c[1]; f.pos = c[2]; f.number = c[3]; f.morph = c[4]; f.lemma = c[5];
      try { f.priority = Integer.parseInt(c[6]); } catch (Exception e) { f.priority = 999; }
      // The old morphology table can contain an obsolete synonym-root choice.
      // Canonical semantic lemma mapping wins while preserving this row's tense/case/person.
      RuForm sem = semanticAliases.get(normRu(f.lemma));
      if (sem != null && sem.pos.equals(f.pos)) f.root = sem.root;
      String k = normRu(f.form);
      RuForm old = ruForms.get(k);
      if (old == null || f.priority < old.priority) ruForms.put(k, f);
    }
    br.close();
  }

  private void loadSemanticAliases(InputStream in) throws IOException
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line = br.readLine();
    while ((line = br.readLine()) != null)
    {
      String[] c = line.split("\\t", -1);
      if (c.length < 8) continue;
      RuForm f = new RuForm();
      f.form=c[0]; f.root=c[1]; f.pos=c[2]; f.number="sg"; f.lemma=c[0];
      f.morph=f.pos.equals("verb") ? "verb:present:3" : "nom";
      try { f.priority=Integer.parseInt(c[3]); } catch(Exception e){ f.priority=50; }
      putBest(semanticAliases,normRu(c[0]),f);
      if(!c[5].isEmpty()) putBest(semanticStems,c[5],f);
      if(!c[6].isEmpty()) putBest(semanticLatin,c[6].toLowerCase(Locale.ROOT),f);
      if(!c[7].isEmpty()) putBest(semanticLatinStems,c[7].toLowerCase(Locale.ROOT),f);
    }
    br.close();
  }

  private static void putBest(Map<String,RuForm> map,String key,RuForm f)
  {
    RuForm old=map.get(key);
    if(old==null || f.priority<old.priority) map.put(key,f);
  }

  private RuForm resolveRussian(String token)
  {
    String n=normRu(token);
    RuForm f=ruForms.get(n); if(f!=null) return f;
    f=semanticAliases.get(n); if(f!=null) return f;
    f=semanticStems.get(russianStem(n)); if(f!=null) return f;
    String low=token.toLowerCase(Locale.ROOT);
    f=semanticLatin.get(low); if(f!=null) return f;
    f=semanticLatinStems.get(latinRussianStem(low)); if(f!=null) return f;
    return null;
  }

  private static String russianStem(String s)
  {
    s=normRu(s); if(s.indexOf(' ')>=0) return s;
    String[] suff={"иями","ями","ами","его","ого","ему","ому","ими","ыми","ешь","ишь","ете","ите","ую","юю","ей","ой","ий","ый","ая","яя","ое","ее","ые","ие","ых","их","ым","им","ом","ем","ах","ях","ам","ям","ов","ев","ть","ти","ся","сь","ала","али","ила","или","ал","ил","ел","ела","ели","ет","ит","ут","ют","ат","ят","ы","и","а","я","у","ю","о","е","ь","й"};
    for(String x:suff) if(s.length()-x.length()>=3 && s.endsWith(x)) return s.substring(0,s.length()-x.length());
    return s;
  }

  private static String latinRussianStem(String s)
  {
    s=s.toLowerCase(Locale.ROOT); if(s.indexOf(' ')>=0) return s;
    String[] suff={"iyami","yami","ami","ogo","ego","emu","omu","imi","ymi","esh","ish","ete","ite","uyu","yuyu","aya","yaya","oe","ee","ye","ie","ykh","ikh","ym","im","om","em","akh","yakh","am","yam","ov","ev","ey","sya","ala","ali","ila","ili","al","il","et","it","ut","yut","at","yat","iy","yy","oy","ti","t","a","i","y","u","o","e"};
    for(String x:suff) if(s.length()-x.length()>=3 && s.endsWith(x)) return s.substring(0,s.length()-x.length());
    return s;
  }

  private static boolean looksLatinRussian(String s)
  {
    String x=s.toLowerCase(Locale.ROOT);
    return x.matches(".*(?:shch|zh|kh|ts|ch|sh|ya|yu|yo).*") || x.matches(".*(?:ogo|emu|ami|ymi|aya|oe|ie|stvo|nie|skiy|skaya|ovat|ivat|it|at|yat)$");
  }

  private boolean isRussianSemanticUnit(String s)
  {
    return isWord(s) || semanticAliases.containsKey(normRu(s)) || semanticLatin.containsKey(s.toLowerCase(Locale.ROOT));
  }

  private List<String> mergeRussianPhrases(List<String> raw)
  {
    List<String> out=new ArrayList<String>();
    for(int i=0;i<raw.size();)
    {
      if(!isWord(raw.get(i))) { out.add(raw.get(i)); i++; continue; }
      int best=1; String bestText=null;
      for(int n=Math.min(6,raw.size()-i);n>=2;n--)
      {
        boolean ok=true; StringBuilder b=new StringBuilder();
        for(int j=0;j<n;j++)
        {
          String q=raw.get(i+j); if(!isWord(q)){ok=false;break;}
          if(j>0)b.append(' '); b.append(q);
        }
        if(!ok) continue;
        String q=b.toString();
        if(semanticAliases.containsKey(normRu(q)) || semanticLatin.containsKey(q.toLowerCase(Locale.ROOT)))
        { best=n; bestText=q; break; }
      }
      if(bestText!=null){out.add(bestText);i+=best;} else {out.add(raw.get(i));i++;}
    }
    return out;
  }

  public String translate(String text)
  {
    if(CYR.matcher(text).find()) return russianToSilmir(text);
    List<String> tt=tokens(text); int sil=0,ru=0;
    for(String tok:tt)
    {
      if(!isWord(tok)) continue;
      A a=analyzeSil(tok); if(a!=null && a.confidence>=80) sil++;
      if(resolveRussian(tok)!=null || looksLatinRussian(tok)) ru++;
    }
    return ru>sil ? russianToSilmir(text) : silmirToRussian(text);
  }

  public String silmirToRussian(String text)
  {
    List<String> t = tokens(text);
    List<A> a = new ArrayList<A>();
    for (String s : t) a.add(isWord(s) ? analyzeSil(s) : null);
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < t.size(); )
    {
      A x = a.get(i);
      if (x == null) { out.add(t.get(i)); i++; continue; }
      if ("genitive".equals(x.kase) && i + 1 < a.size() && a.get(i + 1) != null
          && WORD_POS.contains(a.get(i + 1).pos))
      {
        out.add(toRussian(a.get(i + 1)));
        out.add(toRussian(x));
        i += 2;
      }
      else { out.add(toRussian(x)); i++; }
    }
    return capitalizeLike(text, join(out));
  }

  public String russianToSilmir(String text)
  {
    List<String> t = mergeRussianPhrases(tokens(text));
    List<RuForm> info = new ArrayList<RuForm>();
    for (String s : t) info.add(isRussianSemanticUnit(s) ? resolveRussian(s) : null);

    int verb = -1, subj = -1, obj = -1;
    for (int i = 0; i < info.size(); i++)
      if (info.get(i) != null && "verb".equals(info.get(i).pos) && info.get(i).morph.startsWith("verb:")) { verb = i; break; }
    if (verb >= 0)
    {
      for (int i = verb - 1; i >= 0; i--)
        if (info.get(i) != null && WORD_POS.contains(info.get(i).pos)) { subj = i; break; }
      for (int i = verb + 1; i < info.size(); i++)
      {
        if (info.get(i) != null && WORD_POS.contains(info.get(i).pos))
        {
          String prev = i > 0 ? normRu(t.get(i - 1)) : "";
          if (!RU_PREP.containsKey(prev)) { obj = i; break; }
        }
      }
    }

    Map<Integer, String> dualNext = new HashMap<Integer, String>();
    for (int i = 0; i + 1 < t.size(); i++)
    {
      String s = normRu(t.get(i));
      if (s.equals("два") || s.equals("две")) dualNext.put(i + 1, "absolutive");
      else if (s.equals("двух")) dualNext.put(i + 1, "genitive");
      else if (s.equals("двум")) dualNext.put(i + 1, "dative");
      else if (s.equals("двумя")) dualNext.put(i + 1, "instrumental");
    }

    List<String> out = new ArrayList<String>();
    boolean directObject = obj >= 0;
    for (int i = 0; i < t.size(); i++)
    {
      String tok = t.get(i);
      if (!isRussianSemanticUnit(tok)) { out.add(tok); continue; }
      String nt = normRu(tok);
      if (RU_PREP.containsKey(nt)) continue;
      if (nt.equals("два") || nt.equals("две") || nt.equals("двух") || nt.equals("двум") || nt.equals("двумя")) continue;
      RuForm f = info.get(i);
      if (f == null && nt.startsWith("не") && nt.length()>4)
      {
        RuForm positive=resolveRussian(nt.substring(2));
        if(positive!=null && "adjective".equals(positive.pos))
        { out.add("na"+positive.root); continue; }
      }
      if (f == null) { out.add(transliterateRuToSil(tok)); continue; }
      if ("particle".equals(f.pos) || "numeral".equals(f.pos)) { out.add(f.root); continue; }
      if ("verb".equals(f.pos))
      {
        String[] m = f.morph.split(":");
        String tense = m.length > 1 ? m[1] : "present";
        String person = m.length > 2 ? m[2] : "3";
        String number = f.number.equals("pl") ? "pl" : "sg";
        if (subj >= 0 && info.get(subj) != null)
        {
          RuForm sf = info.get(subj);
          number = sf.number.equals("pl") ? "pl" : "sg";
          if (sf.root.equals("cə")) person="1";
          else if (sf.root.equals("pavil")) person="2";
          else if (sf.root.equals("mæśærel")) { person="1"; number="pl"; }
          else if (sf.root.equals("uneź")) { person="2"; number="pl"; }
          else if (sf.root.equals("ynən")) { person="3"; number="pl"; }
          else person="3";
        }
        out.add(buildVerb(f.root, tense, person, number, "active", "ordinary"));
        continue;
      }
      if (WORD_POS.contains(f.pos))
      {
        String number = f.number.equals("pl") ? "plural" : "singular";
        String kase = "absolutive";
        if (dualNext.containsKey(i)) { number="dual"; kase=dualNext.get(i); }
        String prev = i > 0 ? normRu(t.get(i - 1)) : "";
        String prep = prev;
        if (!RU_PREP.containsKey(prep) && dualNext.containsKey(i) && i > 1)
          prep = normRu(t.get(i - 2));
        if (RU_PREP.containsKey(prep)) kase = RU_PREP.get(prep);
        else if (i == obj) kase = "accusative";
        else if (i == subj && directObject && "noun".equals(f.pos)) kase = "ergative";
        else if (f.morph.equals("gen")) kase="genitive";
        else if (f.morph.equals("dat")) kase="dative";
        else if (f.morph.equals("acc")) kase="accusative";
        else if (f.morph.equals("ins")) kase="instrumental";
        else if (f.morph.equals("loc")) kase="locative";
        if ("pronoun".equals(f.pos)) out.add(f.root + (kase.equals("accusative") ? "ən" : ""));
        else out.add(buildNoun(f.root, number, kase));
        continue;
      }
      if ("adjective".equals(f.pos))
      {
        String ns = "";
        if (i + 1 < info.size() && info.get(i + 1) != null && info.get(i + 1).number.equals("pl")) ns="in";
        if (dualNext.containsKey(i + 1)) ns="et";
        out.add(f.root + ns);
        continue;
      }
      out.add(f.root);
    }
    return capitalizeLike(text, join(out));
  }

  A analyzeSil(String token)
  {
    String low = token.toLowerCase(Locale.ROOT);
    if (PRON.containsKey(low)) { A a=new A(token); a.root=low; a.pos="pronoun"; a.ru=PRON.get(low)[0]; a.confidence=100; return a; }
    RuForm mixedRu = semanticLatin.get(low);
    if (mixedRu != null)
    {
      A a=new A(token); a.root=mixedRu.root; a.pos=mixedRu.pos;
      Lex e=lex.get(mixedRu.root); a.ru=(e!=null?e.ru:mixedRu.lemma); a.confidence=70; return a;
    }
    if (SIL_FUNCTION.containsKey(low)) { A a=new A(token); a.root=low; a.pos="particle"; a.ru=SIL_FUNCTION.get(low); a.confidence=100; return a; }
    if (low.equals("tyt") || low.equals("hremem") || low.equals("lavi"))
    { A a=new A(token); a.root=low; a.pos="irregular"; a.ru=low.equals("tyt")?"иди":low.equals("hremem")?"умойся":"в унитазе"; a.confidence=100; return a; }

    List<A> cand = new ArrayList<A>();
    String[][] cases={{"absolutive",""},{"ergative","əś"},{"accusative","ən"},{"directional","iź"},{"dative","uv"},{"genitive","om"},{"instrumental","yr"},{"locative","ov"},{"ablative","yf"}};
    String[][] nums={{"singular",""},{"dual","et"},{"plural","in"}};
    for (String[] cs : cases)
    {
      if (!cs[1].isEmpty() && !low.endsWith(cs[1])) continue;
      String s1 = cs[1].isEmpty()?low:low.substring(0,low.length()-cs[1].length());
      for (String[] ns : nums)
      {
        if (!ns[1].isEmpty() && !s1.endsWith(ns[1])) continue;
        String root = ns[1].isEmpty()?s1:s1.substring(0,s1.length()-ns[1].length());
        Lex e=lex.get(root);
        if (e != null && (e.pos.equals("noun") || e.pos.equals("pronoun") || e.pos.equals("numeral")))
        { A a=new A(token); a.root=root; a.pos=e.pos; a.number=ns[0]; a.kase=cs[0]; a.ru=e.ru; a.confidence=80+(cs[1].isEmpty()?0:5)+(ns[1].isEmpty()?0:4)+(e.synonym.isEmpty()?5:0); cand.add(a); }
      }
    }
    for (String[] ns : nums)
    {
      if (!ns[1].isEmpty() && !low.endsWith(ns[1])) continue;
      String root=ns[1].isEmpty()?low:low.substring(0,low.length()-ns[1].length()); Lex e=lex.get(root);
      if (e != null && e.pos.equals("adjective")) { A a=new A(token); a.root=root; a.pos="adjective"; a.number=ns[0]; a.ru=e.ru; a.confidence=86+(ns[1].isEmpty()?0:4); cand.add(a); }
    }
    // Active finite verb forms from documented examples; passive/reflexive accept documented slot order.
    String[] regs={"s","l"}; String[] regNames={"ordinary","respectful"};
    String[][] people={{"mn","1","pl"},{"śn","2","pl"},{"m","1","sg"},{"ś","2","sg"},{"n","3","pl"},{"","3","sg"}};
    String[][] voices={{"","active"},{"s","passive"},{"m","reflexive"}};
    for (int ri=0;ri<regs.length;ri++) if (low.endsWith(regs[ri]))
    {
      String body=low.substring(0,low.length()-1);
      for (String[] v:voices) for (String[] p:people)
      {
        String tail=v[0]+p[0]; if (!tail.isEmpty() && !body.endsWith(tail)) continue;
        String b2=tail.isEmpty()?body:body.substring(0,body.length()-tail.length()); if (b2.length()<2) continue;
        char tv=b2.charAt(b2.length()-1); String tense=tv=='a'?"past":tv=='i'?"present":tv=='u'?"future":""; if (tense.isEmpty()) continue;
        String root=b2.substring(0,b2.length()-1); Lex e=lex.get(root);
        if (e!=null && e.pos.equals("verb")) { A a=new A(token); a.root=root; a.pos="verb"; a.tense=tense; a.person=p[1]; a.personNumber=p[2]; a.voice=v[1]; a.register=regNames[ri]; a.ru=e.ru; a.confidence=v[1].equals("active")?95:82; cand.add(a); }
      }
    }
    String[][] ds={{"øjm","imperative_reflexive"},{"øj","imperative"},{"suk","passive_participle"},{"uk","participle"},{"eb","gerund"},{"yvm","superlative"},{"yvs","excessive"},{"yv","comparative"},{"yś","attenuative"},{"of","adverb"},{"yb","adjective_from_noun"},{"yn","agent"},{"yl","action_result"},{"ic","diminutive"},{"uz","augmentative"},{"av","collective"},{"t","ordinal"}};
    for (String[] d:ds) if (low.endsWith(d[0]))
    {
      String root=low.substring(0,low.length()-d[0].length()); Lex e=lex.get(root);
      if (e!=null) { A a=new A(token); a.root=root; a.pos=e.pos.equals("verb")?"verb":"derived"; a.derivation=d[1]; a.ru=e.ru; a.confidence=e.pos.equals("verb")?92:74; cand.add(a); }
    }
    Lex ex=lex.get(low); if (ex!=null) { A a=new A(token); a.root=low; a.pos=ex.pos; a.ru=ex.ru; a.confidence=84+(ex.synonym.isEmpty()?5:0); cand.add(a); }
    if (cand.isEmpty()) { A a=new A(token); a.root=low; a.ru=token; return a; }
    Collections.sort(cand,new Comparator<A>() { public int compare(A x,A y) { int d=y.confidence-x.confidence; return d!=0?d:y.root.length()-x.root.length(); }});
    return cand.get(0);
  }

  String toRussian(A a)
  {
    if (a.pos.equals("unknown") || a.pos.equals("particle") || a.pos.equals("irregular")) return a.ru.isEmpty()?a.token:a.ru;
    if (a.pos.equals("pronoun"))
    {
      String s=ruPronoun(a.root,a.kase); if (a.kase.equals("directional")) return "к "+s; if (a.kase.equals("ablative")) return "от "+s; return s;
    }
    Lex e=lex.get(a.root); String lemma=(e!=null&&e.aliases.length>0)?e.aliases[0]:(e!=null?e.ru:a.ru); lemma=lemma.replaceAll("\\s*\\([^\\)]*\\)\\s*","").trim();
    if (a.pos.equals("noun") || a.pos.equals("numeral"))
    {
      String s=ruInflectNoun(lemma,a.number,a.kase); if (a.kase.equals("locative")) return "в "+s; if (a.kase.equals("ablative")) return "из "+s; if (a.kase.equals("directional")) return "к "+s; return s;
    }
    if (a.pos.equals("adjective")) return adjNumber(lemma,a.number);
    if (a.pos.equals("verb"))
    {
      if (a.derivation.startsWith("imperative")) return imperative(lemma);
      if (a.derivation.equals("gerund")) return lemma+" (деепр.)";
      if (a.derivation.equals("participle") || a.derivation.equals("passive_participle")) return lemma+" (прич.)";
      if (a.tense.equals("future")) return futureAux(a.person,a.personNumber)+" "+lemma;
      String v=conjugate(lemma,a.tense.isEmpty()?"present":a.tense,a.person,a.personNumber); return v==null?lemma:v;
    }
    if (a.pos.equals("derived"))
    {
      if (a.derivation.equals("diminutive")) return "маленький "+lemma;
      if (a.derivation.equals("augmentative")) return "огромный "+lemma;
      if (a.derivation.equals("collective")) return "скопление "+lemma;
      if (a.derivation.equals("adjective_from_noun")) return "относящийся к "+lemma;
      if (a.derivation.equals("agent")) return "деятель: "+lemma;
      if (a.derivation.equals("action_result")) return "результат/действие: "+lemma;
      if (a.derivation.equals("comparative")) return "более "+lemma;
      if (a.derivation.equals("superlative")) return "самый "+lemma;
      if (a.derivation.equals("attenuative")) return "слегка "+lemma;
      if (a.derivation.equals("excessive")) return "слишком "+lemma;
      if (a.derivation.equals("adverb")) return lemma+" образом";
      if (a.derivation.equals("ordinal")) return "порядковый: "+lemma;
    }
    return lemma;
  }

  String buildNoun(String root,String number,String kase)
  {
    String n=number.equals("dual")?"et":number.equals("plural")?"in":"";
    String c=kase.equals("ergative")?"əś":kase.equals("accusative")?"ən":kase.equals("dative")?"uv":kase.equals("genitive")?"om":kase.equals("instrumental")?"yr":kase.equals("locative")?"ov":kase.equals("ablative")?"yf":kase.equals("directional")?"iź":"";
    return root+n+c;
  }

  String buildVerb(String root,String tense,String person,String number,String voice,String register)
  {
    String tv=tense.equals("past")?"a":tense.equals("future")?"u":"i";
    String v=voice.equals("passive")?"s":voice.equals("reflexive")?"m":"";
    String p=person.equals("1")?(number.equals("pl")?"mn":"m"):person.equals("2")?(number.equals("pl")?"śn":"ś"):(number.equals("pl")?"n":"");
    return root+tv+v+p+(register.equals("respectful")?"l":"s");
  }

  String ruInflectNoun(String lemma,String number,String kase)
  {
    String rc=kase.equals("genitive")||kase.equals("ablative")?"gen":kase.equals("dative")||kase.equals("directional")?"dat":kase.equals("accusative")?"acc":kase.equals("instrumental")?"ins":kase.equals("locative")?"loc":"nom";
    if (number.equals("dual"))
    {
      String nn=(kase.equals("absolutive")||kase.equals("ergative")||kase.equals("accusative"))?"sg":"pl";
      String cc=(kase.equals("absolutive")||kase.equals("ergative")||kase.equals("accusative"))?"gen":rc;
      return DUAL_RU.get(kase)+" "+nounForm(lemma,nn,cc);
    }
    return nounForm(lemma,number.equals("plural")?"pl":"sg",rc);
  }

  String nounForm(String lemma,String num,String kase)
  {
    String l=normRu(lemma);
    if (l.equals("камень"))
    {
      String k=num+":"+kase; Map<String,String> m=new HashMap<String,String>();
      m.put("sg:nom","камень");m.put("sg:gen","камня");m.put("sg:dat","камню");m.put("sg:acc","камень");m.put("sg:ins","камнем");m.put("sg:loc","камне");
      m.put("pl:nom","камни");m.put("pl:gen","камней");m.put("pl:dat","камням");m.put("pl:acc","камни");m.put("pl:ins","камнями");m.put("pl:loc","камнях"); return m.containsKey(k)?m.get(k):l;
    }
    String st,gen,dat,acc,ins,loc,pn,pg,pd,pi,pl;
    if (l.endsWith("а")) { st=l.substring(0,l.length()-1); boolean soft="гкхжчшщц".indexOf(st.charAt(st.length()-1))>=0; gen=st+(soft?"и":"ы");dat=st+"е";acc=st+"у";ins=st+"ой";loc=st+"е";pn=st+(soft?"и":"ы");pg=st;pd=st+"ам";pi=st+"ами";pl=st+"ах"; }
    else if (l.endsWith("я")) { st=l.substring(0,l.length()-1);gen=st+"и";dat=st+"е";acc=st+"ю";ins=st+"ей";loc=st+"е";pn=st+"и";pg=st;pd=st+"ям";pi=st+"ями";pl=st+"ях"; }
    else if (l.endsWith("о")) { st=l.substring(0,l.length()-1);gen=st+"а";dat=st+"у";acc=l;ins=st+"ом";loc=st+"е";pn=st+"а";pg=st;pd=st+"ам";pi=st+"ами";pl=st+"ах"; }
    else if (l.endsWith("е")) { st=l.substring(0,l.length()-1);gen=st+"я";dat=st+"ю";acc=l;ins=st+"ем";loc=st+"е";pn=st+"я";pg=st+"й";pd=st+"ям";pi=st+"ями";pl=st+"ях"; }
    else if (l.endsWith("й")) { st=l.substring(0,l.length()-1);gen=st+"я";dat=st+"ю";acc=l;ins=st+"ем";loc=st+"е";pn=st+"и";pg=st+"ев";pd=st+"ям";pi=st+"ями";pl=st+"ях"; }
    else if (l.endsWith("ь")) { st=l.substring(0,l.length()-1);gen=st+"я";dat=st+"ю";acc=l;ins=st+"ем";loc=st+"е";pn=st+"и";pg=st+"ей";pd=st+"ям";pi=st+"ями";pl=st+"ях"; }
    else { st=l;gen=st+"а";dat=st+"у";acc=l;ins=st+"ом";loc=st+"е";boolean soft="гкхжчшщц".indexOf(st.charAt(st.length()-1))>=0;pn=st+(soft?"и":"ы");pg=st+"ов";pd=st+"ам";pi=st+"ами";pl=st+"ах"; }
    if (num.equals("sg")) return kase.equals("gen")?gen:kase.equals("dat")?dat:kase.equals("acc")?acc:kase.equals("ins")?ins:kase.equals("loc")?loc:l;
    return kase.equals("gen")?pg:kase.equals("dat")?pd:kase.equals("acc")?pn:kase.equals("ins")?pi:kase.equals("loc")?pl:pn;
  }

  String conjugate(String lemma,String tense,String person,String num)
  {
    String l=normRu(lemma); String[] arr=null;
    if (l.equals("видеть")) arr=new String[]{"вижу","видишь","видит","видим","видите","видят"};
    else if (l.equals("давать")) arr=new String[]{"даю","даешь","дает","даем","даете","дают"};
    else if (l.equals("идти")) arr=new String[]{"иду","идешь","идет","идем","идете","идут"};
    else if (l.equals("смотреть")) arr=new String[]{"смотрю","смотришь","смотрит","смотрим","смотрите","смотрят"};
    else if (l.equals("существовать")) arr=new String[]{"существую","существуешь","существует","существуем","существуете","существуют"};
    if (tense.equals("past")) { if (l.endsWith("ть")) { String st=l.substring(0,l.length()-2); return st+(num.equals("pl")?"ли":"л"); } return l; }
    if (arr==null)
    {
      if (l.endsWith("овать")) { String st=l.substring(0,l.length()-5); arr=new String[]{st+"ую",st+"уешь",st+"ует",st+"уем",st+"уете",st+"уют"}; }
      else if (l.endsWith("ить")) { String st=l.substring(0,l.length()-3); arr=new String[]{st+"ю",st+"ишь",st+"ит",st+"им",st+"ите",st+"ят"}; }
      else if (l.endsWith("ать")||l.endsWith("ять")||l.endsWith("еть")) { String st=l.substring(0,l.length()-2); arr=new String[]{st+"ю",st+"ешь",st+"ет",st+"ем",st+"ете",st+"ют"}; }
    }
    if (arr==null) return null;
    int i=person.equals("1")?(num.equals("pl")?3:0):person.equals("2")?(num.equals("pl")?4:1):(num.equals("pl")?5:2); return arr[i];
  }

  String ruPronoun(String root,String kase)
  {
    String[] p=PRON.get(root); if (p==null) return root;
    if (kase.equals("dative")||kase.equals("directional")) return p[2];
    if (kase.equals("instrumental")) return p[3];
    if (kase.equals("accusative")||kase.equals("genitive")||kase.equals("ablative")) return p[1]; return p[0];
  }

  static String adjNumber(String l,String n) { if (!n.equals("plural")) return l; if (l.endsWith("ый")||l.endsWith("ой")) return l.substring(0,l.length()-2)+"ые"; if (l.endsWith("ий")) return l.substring(0,l.length()-2)+"ие"; return l; }
  static String imperative(String l) { if (l.endsWith("ить")) return l.substring(0,l.length()-3)+"и"; if (l.endsWith("ть")) return l.substring(0,l.length()-2)+"й"; return l; }
  static String futureAux(String p,String n) { if (p.equals("1")) return n.equals("pl")?"будем":"буду"; if (p.equals("2")) return n.equals("pl")?"будете":"будешь"; return n.equals("pl")?"будут":"будет"; }

  static List<String> tokens(String s) { List<String> r=new ArrayList<String>(); Matcher m=TOKEN.matcher(s); while(m.find()) r.add(m.group()); return r; }
  static boolean isWord(String s) { return s.length()>0 && (Character.isLetter(s.charAt(0)) || s.charAt(0)=='æ' || s.charAt(0)=='ə' || s.charAt(0)=='ø'); }
  static String transliterateRuToSil(String s)
  {
    String low = s.toLowerCase(Locale.ROOT);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < low.length(); i++)
    {
      char c = low.charAt(i);
      switch (c)
      {
        case 'а': b.append("a"); break;
        case 'б': b.append("b"); break;
        case 'в': b.append("v"); break;
        case 'г': b.append("g"); break;
        case 'д': b.append("d"); break;
        case 'е': b.append("e"); break;
        case 'ё': b.append("jo"); break;
        case 'ж': b.append("ź"); break;
        case 'з': b.append("z"); break;
        case 'и': b.append("i"); break;
        case 'й': b.append("j"); break;
        case 'к': b.append("k"); break;
        case 'л': b.append("l"); break;
        case 'м': b.append("m"); break;
        case 'н': b.append("n"); break;
        case 'о': b.append("o"); break;
        case 'п': b.append("p"); break;
        case 'р': b.append("r"); break;
        case 'с': b.append("s"); break;
        case 'т': b.append("t"); break;
        case 'у': b.append("u"); break;
        case 'ф': b.append("f"); break;
        case 'х': b.append("h"); break;
        case 'ц': b.append("ts"); break;
        case 'ч': b.append("tś"); break;
        case 'ш': b.append("ś"); break;
        case 'щ': b.append("śś"); break;
        case 'ъ': break;
        case 'ы': b.append("y"); break;
        case 'ь': break;
        case 'э': b.append("e"); break;
        case 'ю': b.append("ju"); break;
        case 'я': b.append("ja"); break;
        default:
          if (Character.isLetterOrDigit(c)) b.append(c);
          break;
      }
    }
    return b.toString();
  }

  static String normRu(String s) { return s.toLowerCase(Locale.ROOT).replace('ё','е').trim(); }
  static String join(List<String> a)
  {
    StringBuilder b=new StringBuilder();
    for (String s:a)
    {
      if (s.matches("[,.;:!?\\)\\]]")) { while(b.length()>0&&b.charAt(b.length()-1)==' ') b.setLength(b.length()-1); b.append(s); }
      else { if (b.length()>0 && b.charAt(b.length()-1)!=' ' && b.charAt(b.length()-1)!='(' && b.charAt(b.length()-1)!='[') b.append(' '); b.append(s); }
    }
    return b.toString().trim();
  }
  static String capitalizeLike(String src,String out) { if (src.length()>0 && Character.isUpperCase(src.charAt(0)) && out.length()>0) return out.substring(0,1).toUpperCase(Locale.ROOT)+out.substring(1); return out; }
  static Set<String> set(String...x) { Set<String>s=new HashSet<String>(); Collections.addAll(s,x); return s; }
}
