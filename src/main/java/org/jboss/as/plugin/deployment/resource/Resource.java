/*
* JBoss, Home of Professional Open Source.
* Copyright 2012, Red Hat, Inc., and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/

package org.jboss.as.plugin.deployment.resource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines a resource.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class Resource {

    /**
     * The operation address.
     *
     * @parameter
     */
    private String address;

    /**
     * Only adds the resource if the resource does not already exist. If the resource already exists, the resource is
     * skipped.
     *
     * @parameter
     */
    private boolean addIfAbsent;

    /**
     * The operation properties for the resource.
     *
     * @parameter
     */
    private Map<String, String> properties;

    /**
     * Flag to start the operation, if necessary
     *
     * @parameter default-value="false" expression="${add-resource.enableResource}"
     */
    private boolean enableResource;

    /**
     * An array of resources that rely on this resource.
     * <p/>
     * Note all resources will be ignored if the {@code <addIfAbsent/>} is defined and his resource is already defined.
     *
     * @parameter
     */
    private Resource[] resources;

    /**
     * Default constructor.
     */
    public Resource() {

    }

    /**
     * Creates a new resource.
     *
     * @param address        the optional address for the resource.
     * @param properties     the properties for the resource.
     * @param enableResource {@code true} if the resource needs to be enabled after it is added.
     */
    Resource(final String address, final Map<String, String> properties, final boolean enableResource) {
        this.address = address;
        this.properties = properties;
        this.enableResource = enableResource;
        this.addIfAbsent = false;
    }

    /**
     * The address for the resource.
     *
     * @return the address.
     */
    public String getAddress() {
        return address;
    }

    /**
     * Whether or not we should add only if the resource is absent.
     *
     * @return {@code true} if the resource should only be added if it does not already exist, otherwise {@code false}.
     */
    public boolean isAddIfAbsent() {
        return addIfAbsent;
    }

    /**
     * The properties for the resource. If no properties were defined an empty map is returned.
     *
     * @return the properties.
     */
    public Map<String, String> getProperties() {
        if (properties == null) {
            return Collections.emptyMap();
        }
        return properties;
    }

    /**
     * Whether or not the enable operation should be performed.
     *
     * @return {@code true} if the enable operation should be performed, otherwise {@code false}.
     */
    public boolean isEnableResource() {
        return enableResource;
    }

    /**
     * Returns an array of resources that depend on this resource.
     * <p/>
     * Note all sub-resources will be ignored if the {@link #isAddIfAbsent()} is defined and his resource is already
     * defined.
     *
     * @return an array of resources that depend on this resource or {@code null} if there are no child resources.
     */
    public Resource[] getResources() {
        return resources;
    }
}
