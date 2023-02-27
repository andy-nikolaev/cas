package org.apereo.cas.prs;

import org.apereo.cas.CasLabels;
import org.apereo.cas.MonitoredRepository;
import org.apereo.cas.PullRequestListener;
import org.apereo.cas.github.PullRequest;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class CasPullRequestListener implements PullRequestListener {
    private final MonitoredRepository repository;

    @Override
    public void onOpenPullRequest(final PullRequest givenPullRequest) {
        val pr = repository.getPullRequest(givenPullRequest.getNumber());
        log.debug("Processing {}", pr);

        if (shouldDisregardPullRequest(pr)) {
            log.info("{} is considered invalid and will not be processed", pr);
            return;
        }
        processLabelReadyForContinuousIntegration(pr);
        processLabelPendingPortForward(pr);
        processMilestoneAssignment(pr);
        processLabelsByFeatures(pr);
        processLabelsByChangeset(pr);
        removeLabelWorkInProgress(pr);
        checkForPullRequestTestCases(pr);
        checkForPullRequestDescription(pr);
    }

    private boolean shouldDisregardPullRequest(final PullRequest pr) {
        return processDependencyUpgradesPullRequests(pr)
               || processLabelSeeMaintenancePolicy(pr)
               || processInvalidPullRequest(pr);
    }

    @SneakyThrows
    private void checkForPullRequestDescription(final PullRequest pr) {
        val committer = repository.getGitHubProperties().getRepository()
            .getCommitters().contains(pr.getUser().getLogin());
        if (!committer && !StringUtils.hasText(pr.getBody()) && !pr.isWorkInProgress() && !pr.isDraft()) {
            var template = IOUtils.toString(new ClassPathResource("template-no-description.md").getInputStream(), StandardCharsets.UTF_8);
            log.info("Pull request {} has no valid description", pr);
            repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);
            repository.addComment(pr, template);
            repository.close(pr);
        }
    }

    private void processLabelsByChangeset(final PullRequest pr) {
        repository.getPullRequestFiles(pr)
            .forEach(file -> {
                var filename = file.getFilename();
                if (filename.contains("api/cas-server-core-api-configuration-model")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_CONFIGURATION);
                }
                if (filename.contains("dependencies.gradle")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_DEPENDENCIES_MODULES);
                } else if (filename.endsWith(".gradle")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_GRADLE_BUILD_RELEASE);
                } else if (filename.contains("gradle.properties")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_GRADLE_BUILD_RELEASE);
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_DEPENDENCIES_MODULES);
                } else if (filename.endsWith(".html") || filename.endsWith(".js") || filename.endsWith(".css")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_USER_INTERFACE_THEMES);
                } else if (filename.endsWith(".md")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_DOCUMENTATION);
                } else if (filename.contains("script.js") || filename.contains("script.json")) {
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_UNIT_INTEGRATION_TESTS);
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_PUPPETEER);
                }
            });
    }

    private void processLabelReadyForContinuousIntegration(final PullRequest pr) {
        val ci = repository.getGitHubProperties().getRepository().getCommitters().contains(pr.getUser().getLogin());
        if (ci && !pr.isLabeledAs(CasLabels.LABEL_CI)) {
            log.info("Pull request {} is for continuous integration", pr);
            repository.labelPullRequestAs(pr, CasLabels.LABEL_CI);
        }
        if (repository.shouldResumeCiBuild(pr)) {
            log.info("Pull request {} should resume CI workflow", pr);
            if (pr.isLabeledAs(CasLabels.LABEL_CI)) {
                repository.removeLabelFrom(pr, CasLabels.LABEL_CI);
            }
            repository.labelPullRequestAs(pr, CasLabels.LABEL_CI);
        }
    }

    @SneakyThrows
    private void checkForPullRequestTestCases(final PullRequest pr) {
        if (pr.isTargetBranchOnHeroku()) {
            log.info("Pull request {} is targeted at a Heroku branch", pr);
            return;
        }

        if (pr.isDraft() || pr.isWorkInProgress()) {
            log.info("Pull request {} is a work-in-progress", pr);
            return;
        }

        val files = repository.getPullRequestFiles(pr);

        val modifiesJava = files.stream().anyMatch(file ->
            !file.getFilename().contains("Tests") && file.getFilename().endsWith(".java"));

        val modifiesUI = files.stream().anyMatch(file ->
            file.getFilename().endsWith(".css") ||
            file.getFilename().endsWith(".html") ||
            file.getFilename().endsWith(".js"));

        if (modifiesJava || modifiesUI) {
            val hasTests = files.stream().anyMatch(file -> file.getFilename().endsWith("Tests.java")
                                                           || file.getFilename().matches(".*puppeteer.*scenarios.*script.*"));
            if (!hasTests) {
                var isCommitter = repository.getGitHubProperties().getRepository().getCommitters().contains(pr.getUser().getLogin());
                if (!isCommitter) {
                    log.info("Pull request {} does not have any tests", pr);
                    if (!pr.isLabeledAs(CasLabels.LABEL_PENDING_NEEDS_TESTS)) {
                        repository.labelPullRequestAs(pr, CasLabels.LABEL_PENDING_NEEDS_TESTS);
                    }
                    repository.createStatusForFailure(pr, "Tests", "Missing unit/integration/browser tests.");
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);
                    repository.labelPullRequestAs(pr, CasLabels.LABEL_SEE_CONTRIBUTOR_GUIDELINES);
                    var template = IOUtils.toString(new ClassPathResource("template-no-tests.md").getInputStream(), StandardCharsets.UTF_8);
                    repository.addComment(pr, template);
                    repository.close(pr);
                }
            } else {
                if (pr.isLabeledAs(CasLabels.LABEL_PENDING_NEEDS_TESTS)) {
                    repository.removeLabelFrom(pr, CasLabels.LABEL_PENDING_NEEDS_TESTS);
                }
                repository.createStatusForSuccess(pr, "Tests", "Good job! A positive pull request.");
            }
        }
    }

    @SneakyThrows
    private boolean processInvalidPullRequest(final PullRequest pr) {
        if (pr.isTargetBranchOnHeroku()) {
            log.info("Pull request {} is targeted at a Heroku branch", pr);
            return true;
        }

        if (pr.isDraft() || pr.isWorkInProgress()) {
            log.info("Pull request {} is a work-in-progress or is under review", pr);
            return true;
        }

        if (!pr.isUnderReview()) {
            val count = repository.getPullRequestFiles(pr).stream()
                .filter(file -> {
                    var filename = file.getFilename();
                    return !filename.contains("src/test/java")
                           && !filename.endsWith(".html")
                           && !filename.endsWith(".properties")
                           && !filename.endsWith(".js")
                           && !filename.endsWith(".yml")
                           && !filename.endsWith(".yaml")
                           && !filename.endsWith(".json")
                           && !filename.endsWith(".jpg")
                           && !filename.endsWith(".jpeg")
                           && !filename.endsWith(".sh")
                           && !filename.endsWith(".bat")
                           && !filename.endsWith(".txt")
                           && !filename.endsWith(".md")
                           && !filename.endsWith(".gif")
                           && !filename.endsWith(".css");
                })
                .count();

            if (count >= repository.getGitHubProperties().getMaximumChangedFiles()) {
                log.info("Closing invalid pull request {} with large number of changes", pr);
                repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);
                repository.labelPullRequestAs(pr, CasLabels.LABEL_SEE_CONTRIBUTOR_GUIDELINES);

                var template = IOUtils.toString(new ClassPathResource("template-large-patch.md").getInputStream(), StandardCharsets.UTF_8);
                repository.addComment(pr, template);
                repository.close(pr);
                return true;
            }
        }

        if (pr.getTitle().matches("Update\\s\\w.java")) {
            log.info("Closing invalid pull request {} with a bad description/title", pr);
            repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);
            repository.labelPullRequestAs(pr, CasLabels.LABEL_SEE_CONTRIBUTOR_GUIDELINES);
            var template = IOUtils.toString(new ClassPathResource("template-no-description.md").getInputStream(), StandardCharsets.UTF_8);
            repository.addComment(pr, template);
            repository.close(pr);
            return true;
        }
        return false;
    }

    private void removeLabelWorkInProgress(final PullRequest pr) {
        if (pr.isDraft()) {
            if (pr.isLabeledAs(CasLabels.LABEL_PENDING)) {
                repository.removeLabelFrom(pr, CasLabels.LABEL_PENDING);
            }
            if (!pr.isLabeledAs(CasLabels.LABEL_WIP)) {
                repository.labelPullRequestAs(pr, CasLabels.LABEL_WIP);
            }
        } else if (pr.isLabeledAs(CasLabels.LABEL_WIP)) {
            if (pr.isLabeledAs(CasLabels.LABEL_PENDING)) {
                repository.removeLabelFrom(pr, CasLabels.LABEL_PENDING);
            }
            val title = pr.getTitle().toLowerCase();
            if (CasLabels.LABEL_WIP.getKeywords() != null && !CasLabels.LABEL_WIP.getKeywords().matcher(title).find()) {
                log.info("{} will remove the label {}", pr, CasLabels.LABEL_WIP);
                repository.removeLabelFrom(pr, CasLabels.LABEL_WIP);
            }
        }
    }

    @SneakyThrows
    private boolean processDependencyUpgradesPullRequests(final PullRequest pr) {
        var committer = repository.getGitHubProperties().getRepository().getCommitters().contains(pr.getUser().getLogin());
        if (!committer && !pr.isLabeledAs(CasLabels.LABEL_SEE_SECURITY_POLICY)
            && !pr.isTargetBranchOnHeroku() && !pr.isWorkInProgress() && !pr.isDraft()) {
            var files = repository.getPullRequestFiles(pr);
            if (files.size() == 1 && files.get(0).getFilename().endsWith("gradle.properties")) {
                var template = IOUtils.toString(new ClassPathResource("template-security-policy.md").getInputStream(), StandardCharsets.UTF_8);
                repository.addComment(pr, template);
                repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);
                repository.labelPullRequestAs(pr, CasLabels.LABEL_SEE_SECURITY_POLICY);
                repository.close(pr);
                return true;
            }
        }
        return false;
    }

    @SneakyThrows
    private boolean processLabelSeeMaintenancePolicy(final PullRequest pr) {
        if (!pr.isTargetedAtMasterBranch() && !pr.isLabeledAs(CasLabels.LABEL_SEE_MAINTENANCE_POLICY)
            && !pr.isTargetBranchOnHeroku() && !pr.isWorkInProgress()) {
            var milestones = repository.getActiveMilestones();
            val milestone = MonitoredRepository.getMilestoneForBranch(milestones, pr.getBase().getRef());
            if (milestone.isEmpty()) {
                log.info("{} is targeted at a branch {} that is no longer maintained. See maintenance policy", pr, pr.getBase());
                repository.labelPullRequestAs(pr, CasLabels.LABEL_SEE_MAINTENANCE_POLICY);
                repository.labelPullRequestAs(pr, CasLabels.LABEL_PROPOSAL_DECLINED);

                var template = IOUtils.toString(new ClassPathResource("template-maintenance-policy.md").getInputStream(), StandardCharsets.UTF_8);
                repository.addComment(pr, template);
                repository.close(pr);
                return true;
            }
        }
        return false;
    }

    private void processLabelPendingPortForward(final PullRequest pr) {
        if (!pr.isTargetBranchOnHeroku() && !pr.getBase().isRefMaster()
            && !pr.isLabeledAs(CasLabels.LABEL_PENDING_PORT_FORWARD)) {
            log.info("{} is targeted at a branch {} and should be ported forward to the master branch", pr, pr.getBase());
            repository.labelPullRequestAs(pr, CasLabels.LABEL_PENDING_PORT_FORWARD);
        }
    }

    private void processMilestoneAssignment(final PullRequest pr) {
        if (pr.getMilestone() == null && !pr.isTargetBranchOnHeroku()) {
            if (pr.isTargetedAtMasterBranch()) {
                val milestoneForMaster = repository.getMilestoneForMaster();
                milestoneForMaster.ifPresent(milestone -> {
                    log.info("{} will be assigned the master milestone {}", pr, milestone);
                    repository.getGitHub().setMilestone(pr, milestone);
                });
            } else {
                var milestones = repository.getActiveMilestones();
                val milestone = MonitoredRepository.getMilestoneForBranch(milestones, pr.getBase().getRef());
                milestone.ifPresent(result -> {
                    log.info("{} will be assigned the maintenance milestone {}", pr, milestone);
                    repository.getGitHub().setMilestone(pr, result);
                });
            }
        }
    }

    private void processLabelsByFeatures(final PullRequest givenPullRequest) {
        val pr = repository.getPullRequest(givenPullRequest.getNumber());
        val title = pr.getTitle().toLowerCase();
        Arrays.stream(CasLabels.values()).forEach(l -> {
            if (!pr.isLabeledAs(l)) {
                val titlePattern = Pattern.compile("\\b" + l.getTitle().toLowerCase() + ":*\\b", Pattern.CASE_INSENSITIVE);
                boolean assign = false;
                if (titlePattern.matcher(pr.getTitle()).find()) {
                    log.info("{} will be assigned the label {}", pr, l);
                    assign = true;
                } else if (l.getKeywords() != null && l.getKeywords().matcher(title).find()) {
                    log.info("{} will be assigned the label {} by keywords", pr, l);
                    assign = true;
                }
                if (assign) {
                    val ci = l == CasLabels.LABEL_CI
                             && repository.getGitHubProperties().getRepository().getCommitters().contains(pr.getUser().getLogin());
                    if (l != CasLabels.LABEL_CI || ci) {
                        log.info("Assigning label {} to pr {}", l, pr);
                        repository.labelPullRequestAs(pr, l);
                    }
                }
            }
        });
    }
}
