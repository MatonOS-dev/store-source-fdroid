package org.matonos.store.fdroid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repository candidate assembly and source-owned popularity metadata. */
final class FdroidCatalog {
  private FdroidCatalog() { }

  /** Rank from server download counts (log scale), with an anti-feature penalty. */
  static double popularityScore(Map<String,Object> app,Map<String,Map<String,Object>> serverStats) {
    String id=text(app.get("id")).toLowerCase(java.util.Locale.ROOT);Map<String,Object> stats=serverStats.get(id);
    if(stats==null)stats=java.util.Collections.emptyMap();
    double downloads=Math.min(6.0,Math.log1p(Math.max(0,number(stats.get("downloads"))))*0.75);
    Object anti=app.get("antiFeatures");int antiCount=anti instanceof List?((List<?>)anti).size():0;
    double penalty=Math.min(2.0,antiCount*0.75);
    return Math.round(Math.max(0,Math.min(10,downloads*(10.0/6.0)-penalty))*10.0)/10.0;
  }

  private static long number(Object value){return value instanceof Number?((Number)value).longValue():0;}
  private static String text(Object value){return value==null?"":String.valueOf(value);}

  static Map<String,Object> repository(List<Map<String,Object>> repositories,String id){
    for(Map<String,Object> repo:repositories)if(id.equals(repo.get("id"))||id.equals(repo.get("url")))return repo;
    throw new IllegalArgumentException("Unknown repository "+id);
  }
}
