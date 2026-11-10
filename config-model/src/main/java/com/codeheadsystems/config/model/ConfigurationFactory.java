package com.codeheadsystems.config.model;

public interface ConfigurationFactory {

  Configuration build(AppEnvironment appEnvironment);

}
