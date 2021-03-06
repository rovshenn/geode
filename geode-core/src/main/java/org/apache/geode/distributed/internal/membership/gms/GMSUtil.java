/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.distributed.internal.membership.gms;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.geode.GemFireConfigException;
import org.apache.geode.distributed.internal.membership.gms.membership.HostAddress;
import org.apache.geode.internal.net.SocketCreator;

public class GMSUtil {

  /**
   * parse locators & check that the resulting address is compatible with the given address
   *
   * @param locatorsString a DistributionConfig "locators" string
   * @param bindAddress optional address to check for loopback compatibility
   * @return addresses of locators
   */
  public static List<HostAddress> parseLocators(String locatorsString, String bindAddress) {
    InetAddress addr = null;

    try {
      if (bindAddress == null || bindAddress.trim().length() == 0) {
        addr = SocketCreator.getLocalHost();
      } else {
        addr = InetAddress.getByName(bindAddress);
      }
    } catch (UnknownHostException e) {
      // ignore
    }
    return parseLocators(locatorsString, addr);
  }

  /**
   * parse locators & check that the resulting address is compatible with the given address
   *
   * @param locatorsString a DistributionConfig "locators" string
   * @param bindAddress optional address to check for loopback compatibility
   * @return addresses of locators
   */
  public static List<HostAddress> parseLocators(String locatorsString, InetAddress bindAddress) {
    List<HostAddress> result = new ArrayList<>(2);
    Set<InetSocketAddress> inetAddresses = new HashSet<>();
    String host;
    int port;
    boolean checkLoopback = (bindAddress != null);
    boolean isLoopback = (checkLoopback && bindAddress.isLoopbackAddress());

    StringTokenizer parts = new StringTokenizer(locatorsString, ",");
    while (parts.hasMoreTokens()) {
      try {
        String str = parts.nextToken();
        host = str.substring(0, str.indexOf('['));
        int idx = host.lastIndexOf('@');
        if (idx < 0) {
          idx = host.lastIndexOf(':');
        }
        String start = host.substring(0, idx > -1 ? idx : host.length());
        if (start.indexOf(':') >= 0) { // a single numeric ipv6 address
          idx = host.lastIndexOf('@');
        }
        if (idx >= 0) {
          host = host.substring(idx + 1, host.length());
        }

        int startIdx = str.indexOf('[') + 1;
        int endIdx = str.indexOf(']');
        port = Integer.parseInt(str.substring(startIdx, endIdx));
        InetSocketAddress isa = new InetSocketAddress(host, port);

        if (checkLoopback) {
          if (isLoopback && !isa.getAddress().isLoopbackAddress()) {
            throw new GemFireConfigException(
                "This process is attempting to join with a loopback address (" + bindAddress
                    + ") using a locator that does not have a local address (" + isa
                    + ").  On Unix this usually means that /etc/hosts is misconfigured.");
          }
        }
        HostAddress la = new HostAddress(isa, host);
        if (!inetAddresses.contains(isa)) {
          inetAddresses.add(isa);
          result.add(la);
        }
      } catch (NumberFormatException e) {
        // this shouldn't happen because the config has already been parsed and
        // validated
      }
    }

    return result;
  }

  /**
   * replaces all occurrences of a given string in the properties argument with the given value
   */
  public static String replaceStrings(String properties, String property, String value) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    int index = properties.indexOf(property);
    while (index != -1) {
      sb.append(properties.substring(start, index));
      sb.append(value);

      start = index + property.length();
      index = properties.indexOf(property, start);
    }
    sb.append(properties.substring(start));
    return sb.toString();
  }

}
