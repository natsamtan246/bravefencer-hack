package brm.hack;

import java.util.Map;
import java.util.HashMap;

public class RelocationMap {

    private static final Map<Integer,Integer> map = new HashMap<>();

    public static void put(int oldAddr, int newAddr) {
        map.put(oldAddr, newAddr);
    }

    public static Integer get(int oldAddr) {
        return map.get(oldAddr);
    }

    public static boolean contains(int oldAddr) {
        return map.containsKey(oldAddr);
    }

    public static Map<Integer,Integer> all() {
        return map;
    }

    public static void clear() {
        map.clear();
    }
}