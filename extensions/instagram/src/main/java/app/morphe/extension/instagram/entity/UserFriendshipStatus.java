/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.entity;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import app.morphe.extension.crimera.PikoUtils;

public class UserFriendshipStatus extends Entity {
    private final Object obj;
    private static Class<?> helperClass;
    private static Class<?> friendshipStatusClass;

    public UserFriendshipStatus(Object obj) {
        super(obj);
        this.obj = obj;
    }

    public Map<String, Boolean> getMappings(){
        // Users decoded without friendship_status have none yet; the mapper would throw on them.
        if (this.obj == null) return Collections.emptyMap();
        try {
            if (helperClass == null) helperClass = Class.forName("classname");
            if (friendshipStatusClass == null) friendshipStatusClass = Class.forName("friendshipstatusclass");
            return (Map) super.getMethod(helperClass, "methodname",
                    new Class[]{friendshipStatusClass}, this.obj);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return new HashMap();
    }

    private Boolean getValue(String key) throws Exception {
        Map<String, Boolean> mappings = getMappings();
        return mappings.getOrDefault(key,false);
    }

    public Boolean getFollowBackStatus() throws Exception {
        return getValue("followed_by");
    }

    public Boolean getFollowingStatus() throws Exception {
        return getValue("following");
    }
}
