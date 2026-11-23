package io.github.offlineglass.hook.adapters.bilibili;

import java.util.*;

/** Labels are semantic identities; positions always come from the current native row. */
public final class BilibiliHomeChannels {
    private static final Set<String> PINNED=Set.of("直播","推荐","影视");
    public static List<String> visible(List<String> nativeOrder,String selected) {
        List<String> result=new ArrayList<>();
        for(String label:nativeOrder) if((PINNED.contains(label) || label.equals(selected)) && !result.contains(label)) result.add(label);
        return result;
    }
    /** Full native order: strip adjacency then mirrors the pager, so a swipe
     *  always lands on the channel shown next to the indicator. */
    public static List<String> all(List<String> nativeOrder) {
        List<String> result=new ArrayList<>();
        for(String label:nativeOrder) if(label!=null && !label.isBlank() && !result.contains(label)) result.add(label);
        return result;
    }

    /** Drop transient over-wide campaign labels (hot-word/activity channels)
     *  from wide-strip candidates. Such a label can be twice as wide as any
     *  regular tab, pushing the measured span past the screen budget and
     *  collapsing the whole strip into the 3-pin fallback (seen on-device:
     *  candidates=7 widest=240 needed=2174 > maxSpan=1883.7). The native pager
     *  and the More action still reach the trimmed channel; pins and the
     *  currently selected channel are never dropped. Falls back to the input
     *  when fewer than 3 candidates would remain. */
    public static List<String> trimOverWide(List<String> labels,String selected,java.util.function.Function<String,Float> measure) {
        Float base=null;
        for(String label:labels) if(PINNED.contains(label)) { float w=measure.apply(label); base=base==null?w:Math.max(base,w); }
        if(base==null || base<=0f) return labels;
        float limit=base*1.5f;
        List<String> out=new ArrayList<>();
        for(String label:labels) if(PINNED.contains(label) || label.equals(selected) || measure.apply(label)<=limit) out.add(label);
        return out.size()>=3?out:labels;
    }
    public static Map<String,Float> positions(List<String> labels) {
        Map<String,Float> result=new LinkedHashMap<>();
        int count=labels.size()+1;
        result.put("",0.5f/count); // Existing 'more' action remains the first slot.
        for(int i=0;i<labels.size();i++)result.put(labels.get(i),(i+1.5f)/count);
        return result;
    }
}
