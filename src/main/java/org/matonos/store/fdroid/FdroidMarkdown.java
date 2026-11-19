package org.matonos.store.fdroid;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the supported F-Droid HTML description subset to safe CommonMark text. */
final class FdroidMarkdown {
  private static final Pattern TOKEN = Pattern.compile("(?is)<(/?)(p|br|b|strong|i|em|code|ul|ol|li|h[1-6]|a)\\b([^>]*)>");
  private static final Pattern HREF = Pattern.compile("(?is)href\\s*=\\s*['\"](https?://[^'\"]+)['\"]");
  static String convert(String html) {
    if (html == null || html.isEmpty()) return "";
    Matcher matcher=TOKEN.matcher(html);StringBuilder out=new StringBuilder();int cursor=0;int listDepth=0;String link=null;
    while(matcher.find()) {
      escape(out,html.substring(cursor,matcher.start()));boolean close=!matcher.group(1).isEmpty();String tag=matcher.group(2).toLowerCase(Locale.ROOT);
      if(tag.equals("p")||tag.matches("h[1-6]")) paragraphBreak(out);
      else if(tag.equals("li")||tag.equals("br")) newline(out);
      if(tag.equals("ul")||tag.equals("ol")){if(!close)listDepth++;else listDepth=Math.max(0,listDepth-1);newline(out);}
      if(tag.equals("li")&&!close)out.append(listDepth>0?"- ":"- ");
      if(tag.matches("h[1-6]")&&!close)out.append("#".repeat(Integer.parseInt(tag.substring(1)))).append(' ');
      if(tag.equals("a")&&!close){Matcher href=HREF.matcher(matcher.group(3));link=href.find()?href.group(1):null;if(link!=null)out.append('[');}
      if(tag.equals("a")&&close&&link!=null){out.append("](").append(link).append(')');link=null;}
      if(tag.equals("b")||tag.equals("strong"))out.append(close?"**":"**");
      if(tag.equals("i")||tag.equals("em"))out.append(close?"*":"*");
      if(tag.equals("code"))out.append('`');
      if((tag.equals("p")||tag.matches("h[1-6]"))&&close)paragraphBreak(out);
      if(tag.equals("li")&&close)newline(out);
      cursor=matcher.end();
    }
    escape(out,html.substring(cursor));return out.toString().replaceAll("[ \\t]+\\n","\n").replaceAll("\\n{3,}","\n\n").trim();
  }
  private static void newline(StringBuilder out){while(out.length()>0&&out.charAt(out.length()-1)==' ')out.setLength(out.length()-1);if(out.length()>0&&out.charAt(out.length()-1)!='\n')out.append('\n');}
  private static void paragraphBreak(StringBuilder out){while(out.length()>0&&out.charAt(out.length()-1)==' ')out.setLength(out.length()-1);if(out.length()==0)return;if(trailingNewlines(out)<2)out.append("\n\n");}
  private static int trailingNewlines(StringBuilder out){int count=0;for(int i=out.length()-1;i>=0&&out.charAt(i)=='\n';i--)count++;return count;}
  private static void escape(StringBuilder out,String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='&'){int end=text.indexOf(';',i+1);if(end>i&&end-i<12){String entity=text.substring(i+1,end);String decoded=decode(entity);if(decoded!=null){out.append(decoded);i=end;continue;}}}if("\\`*_{}[]<>#+-.!|".indexOf(c)>=0)out.append('\\');out.append(c);}}
  private static String decode(String value){switch(value){case "amp":return "&";case "lt":return "<";case "gt":return ">";case "quot":return "\"";case "apos":return "'";case "nbsp":return " ";default:try{if(value.startsWith("#x"))return String.valueOf((char)Integer.parseInt(value.substring(2),16));if(value.startsWith("#"))return String.valueOf((char)Integer.parseInt(value.substring(1)));}catch(NumberFormatException ignored){}return null;}}
  private FdroidMarkdown(){}
}
