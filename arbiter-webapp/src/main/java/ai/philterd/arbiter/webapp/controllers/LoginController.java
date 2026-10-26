/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.service.SignInSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final boolean philterSignIn;

    public LoginController(@Value("${arbiter.sign-in.mode:local}") final String signInMode) {
        this.philterSignIn = SignInSettings.MODE_PHILTER.equalsIgnoreCase(signInMode == null ? "" : signInMode.trim());
    }

    @GetMapping("/login")
    public String login(final Model model) {
        model.addAttribute("philterSignIn", philterSignIn);
        return "login";
    }

}
