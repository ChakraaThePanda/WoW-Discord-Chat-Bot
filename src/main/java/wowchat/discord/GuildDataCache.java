package wowchat.discord;

import wowchat.game.GuildMember;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.common.Global$;
import scala.Option;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * GuildDataCache
 * 
 * Centralized cache for guild roster data to avoid redundant reflection calls
 * and duplicate iterations. Updated once per tick cycle, read by all guild publishers.
 * 
 * Thread-safe with read/write locks for concurrent access.
 */
public final class GuildDataCache {
    
    // Singleton instance
    private static final GuildDataCache INSTANCE = new GuildDataCache();
    
    // Thread safety
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Cached data
    private volatile long lastUpdateTime = 0;
    private Map<String, GuildMember> memberByName = new LinkedHashMap<>();
    private Map<String, String> officerNotes = new LinkedHashMap<>();
    private Set<String> ignoreLower = Collections.emptySet();
    private scala.collection.mutable.Map<Object, GuildMember> rawRoster = null;
    
    private GuildDataCache() {}
    
    public static GuildDataCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * Refresh cache from GamePacketHandler.
     * Call this once at the start of GuildRosterPublisher.tick()
     */
    public void refresh() {
        lock.writeLock().lock();
        try {
            // Fetch raw roster via reflection
            rawRoster = fetchRawRoster();
            if (rawRoster == null) {
                memberByName.clear();
                officerNotes.clear();
                lastUpdateTime = System.currentTimeMillis();
                return;
            }
            
            // Build processed maps
            Map<String, GuildMember> newMembers = new LinkedHashMap<>();
            Map<String, String> newNotes = new LinkedHashMap<>();
            
            scala.collection.Iterator<GuildMember> it = rawRoster.valuesIterator();
            while (it.hasNext()) {
                GuildMember m = it.next();
                String name = m.name();
                newMembers.put(name, m);
                
                String note = m.officerNote() != null ? m.officerNote().trim() : "";
                newNotes.put(name, note);
            }
            
            memberByName = newMembers;
            officerNotes = newNotes;
            lastUpdateTime = System.currentTimeMillis();
            
        } catch (Throwable t) {
            System.err.println("[GuildDataCache] Refresh error: " + t.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Update the ignore list (called when config reloads)
     */
    public void setIgnoreList(Set<String> ignoreList) {
        lock.writeLock().lock();
        try {
            this.ignoreLower = ignoreList != null 
                ? Collections.unmodifiableSet(new HashSet<>(ignoreList))
                : Collections.emptySet();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get the raw Scala roster map (for backward compatibility)
     */
    public scala.collection.mutable.Map<Object, GuildMember> getRawRoster() {
        lock.readLock().lock();
        try {
            return rawRoster;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get member by name
     */
    public GuildMember getMember(String name) {
        lock.readLock().lock();
        try {
            return memberByName.get(name);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get all members (filtered by ignore list if requested)
     */
    public Collection<GuildMember> getMembers(boolean applyIgnoreFilter) {
        lock.readLock().lock();
        try {
            if (!applyIgnoreFilter || ignoreLower.isEmpty()) {
                return new ArrayList<>(memberByName.values());
            }
            
            List<GuildMember> filtered = new ArrayList<>();
            for (Map.Entry<String, GuildMember> entry : memberByName.entrySet()) {
                if (!ignoreLower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    filtered.add(entry.getValue());
                }
            }
            return filtered;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get officer notes filtered by ignore list
     */
    public Map<String, String> getOfficerNotes(boolean applyIgnoreFilter) {
        lock.readLock().lock();
        try {
            if (!applyIgnoreFilter || ignoreLower.isEmpty()) {
                return new LinkedHashMap<>(officerNotes);
            }
            
            Map<String, String> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : officerNotes.entrySet()) {
                if (!ignoreLower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    filtered.put(entry.getKey(), entry.getValue());
                }
            }
            return filtered;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if cache is empty
     */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return memberByName.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------
    
    private static scala.collection.mutable.Map<Object, GuildMember> fetchRawRoster() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return null;
            
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return null;
            
            GamePacketHandler gph = (GamePacketHandler) handler;
            scala.collection.mutable.Map<Object, GuildMember> roster = gph.getGuildRoster();
            
            return (roster != null && !roster.isEmpty()) ? roster : null;
        } catch (Throwable t) {
            System.err.println("[GuildDataCache] Error fetching roster: " + t.getMessage());
            return null;
        }
    }
}
