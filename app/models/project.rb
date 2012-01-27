# require 'backend'

class Project
  include Mongoid::Document
  include Mongoid::Timestamps
  include Mongoid::Versioning

  field :vcs_url
  field :ssh_private_key
  field :ssh_public_key
  field :visible, :type => Boolean, :default => false

  # setup
  # TECHNICAL_DEBT: this is actually the "pre-setup" field
  field :setup, :type => String, :default => ""

  # TECHNICAL_DEBT: this is actually marked "setup" on the form
  field :dependencies, :type => String, :default => ""

  # TECHNICAL_DEBT: remove this, it will soon be unused
  field :compile, :type => String, :default => ""

  # test settings
  field :test, :type => String, :default => ""
  field :extra, :type => String, :default => ""

  # Notifications
  field :hipchat_room
  field :hipchat_api_token



  has_and_belongs_to_many :users
#  has_many :builds

  attr_accessible :setup, :dependencies, :compile, :test, :extra, :hipchat_room, :hipchat_api_token

  def to_param
    github_project_name
  end

  def self.from_url(url)
    projects = Project.where(:vcs_url => url)
    projects.first
  end

  def self.from_github_name(name)
    url = Backend.blocking_worker "circle.backend.github-url/canonical-url", name
    self.from_url(url)
  end

  # TECHNICAL_DEBT: projects should have a list of builds, but it doesnt on the
  # clojure side. That would kill basically all the code that follows!
  def recent_builds(limit=10)
    Build.where(:vcs_url => vcs_url).order_by([[:start_time, :desc]]).limit(limit)
  end

  def latest_build
    recent_builds(1).first
  end

  def build_numbered(num) # bad things happen if we call this "build"
    Build.where(:vcs_url => vcs_url, :build_num => num).first
  end

  def github_project_name
    result = vcs_url.sub("https://github.com/", "")
    if result[-1] == "/" then
      result = result[0..-2]
    end
    result
  end

  def config
    # For now, just read circle.yml for everyone, and see what happens.
    File.read("#{File.dirname(__FILE__)}/../../circle.yml")
  end

  def as_json(options={})
    super options.merge(:only => Project.accessible_attributes.to_a + [:vcs_url, :_id])
  end

  # Allows mass-assignment, only for use from testing
  def self.unsafe_create(attrs)
    raise if !Rails.env.test?

    p = Project.create!
    attrs.each { |k, v| p.send("#{k}=", v) }
    p
  end
end
