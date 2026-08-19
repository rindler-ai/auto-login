#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

ROOT = File.expand_path("..", __dir__)
WORKFLOW_PATHS = %w[
  .github/workflows/ci.yml
  .github/workflows/release.yml
].freeze
ALLOWED_RUNNERS = [
  %w[self-hosted Linux X64],
  ["self-hosted", "macOS"]
].freeze
HOSTED_RUNNER = /\A(?:ubuntu|macos|windows)-/i

def load_workflow(relative_path)
  YAML.safe_load(File.read(File.join(ROOT, relative_path)), aliases: false)
end

def executable_jobs(workflow)
  jobs = workflow.fetch("jobs")
  raise "jobs must be a mapping" unless jobs.is_a?(Hash)

  jobs.select do |_name, job|
    job.is_a?(Hash) && (job.key?("steps") || job.key?("runs-on"))
  end
end

def policy_violations(workflow, relative_path)
  executable_jobs(workflow).flat_map do |job_name, job|
    prefix = "#{relative_path}: job #{job_name}"
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

workflows = WORKFLOW_PATHS.to_h { |path| [path, load_workflow(path)] }
violations = workflows.flat_map { |path, workflow| policy_violations(workflow, path) }
abort violations.join("\n") unless violations.empty?

sample = workflows.fetch(".github/workflows/ci.yml")
sample_job_name, = executable_jobs(sample).first

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

puts "Authored workflow runner and timeout policy passed (including mutation checks)."
