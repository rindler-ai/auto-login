#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"
require "fileutils"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
WORKFLOW_GLOB = ".github/workflows/*.{yml,yaml}"
ALLOWED_RUNNERS = [
  %w[self-hosted Linux X64],
  ["self-hosted", "macOS"]
].freeze
HOSTED_RUNNER = /\A(?:ubuntu|macos|windows)-/i

def workflow_documents(root)
  paths = Dir.glob(File.join(root, WORKFLOW_GLOB), File::FNM_DOTMATCH).reject do |path|
    [".", ".."].include?(File.basename(path))
  end

  paths.sort.flat_map do |path|
    relative_path = path.delete_prefix("#{root}/")
    source = File.read(path)
    lines = source.lines
    documents = YAML.parse_stream(source, filename: path).children.map do |document|
      document_source = lines[document.start_line...document.end_line].join
      YAML.safe_load(document_source, aliases: false, filename: path)
    end
    documents.each_with_index.map do |document, index|
      label = documents.one? ? relative_path : "#{relative_path} document #{index + 1}"
      [label, document]
    end
  end
end

def reusable_workflow_caller?(job)
  job.is_a?(Hash) && job.key?("uses") && !job.key?("steps") && !job.key?("runs-on")
end

def regular_jobs(workflow)
  jobs = workflow.fetch("jobs")

  jobs.reject { |_name, job| reusable_workflow_caller?(job) }
end

def policy_violations(workflow, relative_path)
  unless workflow.is_a?(Hash) && workflow["jobs"].is_a?(Hash)
    return ["#{relative_path}: workflow jobs must be a mapping"]
  end

  regular_jobs(workflow).flat_map do |job_name, job|
    prefix = "#{relative_path}: job #{job_name}"
    unless job.is_a?(Hash)
      next ["#{prefix} must be a mapping"]
    end

    runners = job["runs-on"]
    labels = runners.is_a?(Array) ? runners : [runners].compact
    violations = []

    hosted_labels = labels.grep(HOSTED_RUNNER)
    unless hosted_labels.empty?
      violations << "#{prefix} uses GitHub-hosted runner label(s): #{hosted_labels.join(", ")}"
    end

    unless ALLOWED_RUNNERS.include?(runners)
      violations << "#{prefix} runs-on must be exactly [self-hosted, Linux, X64] or [self-hosted, macOS]"
    end

    timeout = job["timeout-minutes"]
    unless timeout.is_a?(Integer) && timeout.positive?
      violations << "#{prefix} timeout-minutes must be a positive integer"
    end

    violations
  end
end

def directory_policy_violations(root)
  workflow_documents(root).flat_map do |relative_path, workflow|
    policy_violations(workflow, relative_path)
  end
end

def deep_copy(value)
  Marshal.load(Marshal.dump(value))
end

def assert_mutation_rejected!(workflow, description)
  mutated = deep_copy(workflow)
  yield mutated
  violations = policy_violations(mutated, "mutation:#{description}")
  return unless violations.empty?

  raise "policy test is vacuous: #{description} mutation was accepted"
end

workflows = workflow_documents(ROOT).to_h
violations = directory_policy_violations(ROOT)
abort violations.join("\n") unless violations.empty?

sample = workflows.fetch(".github/workflows/ci.yml")
sample_job_name, = regular_jobs(sample).first

%w[ubuntu-latest macos-15 windows-latest].each do |hosted_runner|
  assert_mutation_rejected!(sample, "hosted-#{hosted_runner}") do |mutated|
    mutated.fetch("jobs").fetch(sample_job_name)["runs-on"] = hosted_runner
  end
end

assert_mutation_rejected!(sample, "dual-runner-fallback") do |mutated|
  mutated.fetch("jobs").fetch(sample_job_name)["runs-on"] =
    %w[self-hosted Linux X64 ubuntu-latest]
end

assert_mutation_rejected!(sample, "missing-timeout") do |mutated|
  mutated.fetch("jobs").fetch(sample_job_name).delete("timeout-minutes")
end

assert_mutation_rejected!(sample, "zero-timeout") do |mutated|
  mutated.fetch("jobs").fetch(sample_job_name)["timeout-minutes"] = 0
end

reusable_caller = {
  "jobs" => {
    "delegate" => {
      "uses" => "owner/repository/.github/workflows/reusable.yml@main",
      "with" => {"target" => "production"},
      "secrets" => "inherit"
    }
  }
}
unless policy_violations(reusable_caller, "mutation:reusable-caller").empty?
  raise "policy test rejected a uses-only reusable-workflow caller"
end

Dir.mktmpdir("authored-workflows") do |root|
  workflow_dir = File.join(root, ".github", "workflows")
  FileUtils.mkdir_p(workflow_dir)
  File.write(File.join(workflow_dir, "ci.yml"), <<~YAML)
    name: Valid CI
    jobs:
      valid:
        runs-on: [self-hosted, Linux, X64]
        timeout-minutes: 5
        steps:
          - run: "true"
  YAML
  File.write(File.join(workflow_dir, "release.yml"), <<~YAML)
    name: Reusable caller
    jobs:
      delegate:
        uses: owner/repository/.github/workflows/reusable.yml@main
  YAML
  File.write(File.join(workflow_dir, "third.yaml"), <<~YAML)
    name: Additional valid workflow
    jobs:
      valid:
        runs-on: [self-hosted, macOS]
        timeout-minutes: 5
        steps:
          - run: "true"
    ---
    name: Hidden hosted workflow
    jobs:
      hosted:
        runs-on: ubuntu-latest
        timeout-minutes: 5
        steps:
          - run: "true"
  YAML
  File.write(File.join(workflow_dir, ".hosted.yml"), <<~YAML)
    name: Dot-prefixed hosted workflow
    jobs:
      hosted:
        runs-on: ubuntu-latest
        timeout-minutes: 5
        steps:
          - run: "true"
  YAML

  added_workflow_violations = directory_policy_violations(root)
  unless added_workflow_violations.any? do |violation|
    violation.include?("third.yaml document 2") && violation.include?("ubuntu-latest")
  end
    raise "policy test is vacuous: added hosted-runner .yaml workflow was accepted"
  end
  unless added_workflow_violations.any? do |violation|
    violation.include?(".hosted.yml") && violation.include?("ubuntu-latest")
  end
    raise "policy test is vacuous: dot-prefixed hosted-runner workflow was accepted"
  end
end

puts "Authored workflow runner and timeout policy passed (including mutation checks)."
