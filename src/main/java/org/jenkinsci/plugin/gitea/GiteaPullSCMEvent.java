/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugin.gitea;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugin.gitea.client.api.GiteaPullRequest;
import org.jenkinsci.plugin.gitea.client.api.GiteaPullRequestEvent;
import org.jenkinsci.plugin.gitea.client.api.GiteaPullRequestEventType;

/**
 * A {@link SCMHeadEvent} for a {@link GiteaPullRequestEvent}.
 */
public class GiteaPullSCMEvent extends AbstractGiteaSCMHeadEvent<GiteaPullRequestEvent> {
    /**
     * Constructor.
     *
     * @param payload the payload.
     * @param origin  the origin
     */
    public GiteaPullSCMEvent(@NonNull GiteaPullRequestEvent payload, @CheckForNull String origin) {
        super(typeOf(payload), payload, origin);
    }

    /**
     * Determines the {@link Type} of of a pull request event.
     *
     * @param event the pull request event.
     * @return the type.
     */
    @NonNull
    private static Type typeOf(@NonNull GiteaPullRequestEvent event) {
        final GiteaPullRequestEventType action = event.getAction();
        if (action != null) {
            switch (action) {
                case OPENED:
                    return Type.CREATED;
                case CLOSED:
                    return Type.REMOVED;
                case REOPENED:
                default:
                    return Type.UPDATED;
            }
        }
        return Type.UPDATED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String descriptionFor(@NonNull SCMNavigator navigator) {
        GiteaPullRequestEventType action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case OPENED:
                    return "Pull request #" + getPayload().getNumber() + " opened in repository " + getPayload()
                            .getRepository().getName();
                case REOPENED:
                    return "Pull request #" + getPayload().getNumber() + " reopened in repository " + getPayload()
                            .getRepository().getName();
                case CLOSED:
                    return "Pull request #" + getPayload().getNumber() + " closed in repository " + getPayload()
                            .getRepository().getName();
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event in repository " + getPayload().getRepository()
                .getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String descriptionFor(SCMSource source) {
        GiteaPullRequestEventType action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case OPENED:
                    return "Pull request #" + getPayload().getNumber() + " opened";
                case REOPENED:
                    return "Pull request #" + getPayload().getNumber() + " reopened";
                case CLOSED:
                    return "Pull request #" + getPayload().getNumber() + " closed";
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        GiteaPullRequestEventType action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case OPENED:
                    return "Pull request #" + getPayload().getNumber() + " opened in repository " +
                            getPayload().getRepository().getOwner().getUsername() + "/"
                            + getPayload().getRepository().getName();
                case REOPENED:
                    return "Pull request #" + getPayload().getNumber() + " reopened in repository " +
                            getPayload().getRepository().getOwner().getUsername() + "/"
                            + getPayload().getRepository().getName();
                case CLOSED:
                    return "Pull request #" + getPayload().getNumber() + " closed in repository "
                            + getPayload().getRepository().getOwner().getUsername() + "/"
                            + getPayload().getRepository().getName();
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event in repository "
                + getPayload().getRepository().getOwner().getUsername() + "/"
                + getPayload().getRepository().getName();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Map<SCMHead, SCMRevision> headsFor(GiteaSCMSource source) {
        Map<SCMHead, SCMRevision> result = new HashMap<>();
        try (GiteaSCMSourceRequest request = new GiteaSCMSourceContext(null, SCMHeadObserver.none())
                .withTraits(source.getTraits())
                .newRequest(source, null)) {
            if (!request.isFetchPRs()) {
                return result;
            }
            final GiteaPullRequest p = getPayload().getPullRequest();
            if (p == null) {
                // the pull request has been deleted, sadly we have no way to determine where the PR was from
                // so we just blast hit with all potential cases and using dummy values.
                // the values are not important as the event consumers will be matching on SCMHead.getName()
                // and so will see that there is a new "head" for the old name, reconfirm the revision
                // and find that the head no longer exists... and our job is done!
                Set<ChangeRequestCheckoutStrategy> strategies = EnumSet.noneOf(ChangeRequestCheckoutStrategy.class);
                if (request.isFetchForkPRs()) {
                    strategies.addAll(request.getForkPRStrategies());
                }
                if (request.isFetchOriginPRs()) {
                    strategies.addAll(request.getOriginPRStrategies());
                }
                for (ChangeRequestCheckoutStrategy strategy : strategies) {
                    result.put(new PullRequestSCMHead(
                            "PR-" + getPayload().getNumber() + (strategies.size() > 1 ? "-" + strategy.name()
                                    .toLowerCase(Locale.ENGLISH) : ""),
                            getPayload().getNumber(),
                            new BranchSCMHead("dummy-name"),
                            ChangeRequestCheckoutStrategy.MERGE,
                            SCMHeadOrigin.DEFAULT,
                            source.getRepoOwner(),
                            source.getRepository(),
                            "dummy-name",
                            null), null);
                }
            } else {
                String originOwner = p.getHead().getRepo().getOwner().getUsername();
                String originRepository = p.getHead().getRepo().getName();
                Set<ChangeRequestCheckoutStrategy> strategies = request.getPRStrategies(
                        !StringUtils.equalsIgnoreCase(source.getRepoOwner(), originOwner)
                                && StringUtils.equalsIgnoreCase(source.getRepository(), originRepository)
                );
                for (ChangeRequestCheckoutStrategy strategy : strategies) {
                    PullRequestSCMHead h = new PullRequestSCMHead(
                            "PR-" + p.getNumber() + (strategies.size() > 1 ? "-" + strategy.name()
                                    .toLowerCase(Locale.ENGLISH) : ""),
                            p.getNumber(),
                            new BranchSCMHead(p.getBase().getRef()),
                            strategy,
                            StringUtils.equalsIgnoreCase(originOwner, source.getRepoOwner())
                                    && StringUtils.equalsIgnoreCase(originRepository, source.getRepository())
                                    ? SCMHeadOrigin.DEFAULT
                                    : new SCMHeadOrigin.Fork(originOwner + "/" + originRepository),
                            originOwner,
                            originRepository,
                            p.getHead().getRef(),
                            p.getTitle());
                    result.put(h, getPayload().getAction() == GiteaPullRequestEventType.CLOSED
                            ? null
                            : new PullRequestSCMRevision(
                                    h,
                                    new BranchSCMRevision(
                                            h.getTarget(),
                                            p.getBase().getSha()
                                    ),
                                    new BranchSCMRevision(
                                            new BranchSCMHead(h.getOriginName()),
                                            p.getHead().getSha()
                                    )
                            ));
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return result;
    }

    /**
     * Our handler.
     */
    @Extension
    public static class HandlerImpl extends GiteaWebhookHandler<GiteaPullSCMEvent, GiteaPullRequestEvent> {

        public HandlerImpl() {
            super("pull_request");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected GiteaPullSCMEvent createEvent(GiteaPullRequestEvent payload, String origin) {
            return new GiteaPullSCMEvent(payload, origin);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void process(GiteaPullSCMEvent event) {
            SCMHeadEvent.fireNow(event);
        }
    }
}
