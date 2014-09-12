/*
 * Copyright 2014 Google Inc. All rights reserved.
 * Copyright 2014 Yubico.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file or at
 * https://developers.google.com/open-source/licenses/bsd
 */

package com.yubico.u2f.server.impl;

import com.yubico.u2f.server.SessionIdGenerator;

public class SessionIdGeneratorImpl implements SessionIdGenerator {

    private long sessionIdCounter = 0; //TODO: Should this be persisted?

    @Override
    public String generateSessionId(String accountName) {
      return "sessionId_"
              + (sessionIdCounter++)
              + "_"
              + accountName;
    }
}
