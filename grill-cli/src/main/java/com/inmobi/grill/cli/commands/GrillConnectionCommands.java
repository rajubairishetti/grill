package com.inmobi.grill.cli.commands;

/*
 * #%L
 * Grill CLI
 * %%
 * Copyright (C) 2014 Inmobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.google.common.base.Joiner;
import com.inmobi.grill.api.APIResult;
import com.inmobi.grill.client.GrillClient;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.ExitShellRequest;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GrillConnectionCommands implements CommandMarker {
  private GrillClient client;


  public void setClient(GrillClient client) {
    this.client = client;
  }


  @CliCommand(value = "set", help = "sets a session parameter.")
  public String setParam(
      @CliOption(key={"","param"}, mandatory = true, help = "key=val")
      String keyval) {
    String[] pair = keyval.split("=");
    if(pair.length != 2) {
      return "Error: Pass parameter as <key>=<value>";
    }
    APIResult result = client.setConnectionParam(pair[0], pair[1]);
    return result.getMessage();
  }


  @CliCommand(value="show params", help = "list of all session parameter")
  public String showParameters(){
    List<String> params = client.getConnectionParam();
    return Joiner.on("\n").skipNulls().join(params);
  }

  @CliCommand(value="get", help = "gets value of session parameter")
  public String getParam(
      @CliOption(key={"","param"},
          mandatory = true,
          help = "param name")
      String param) {
    return Joiner.on("\n").skipNulls().join(client.getConnectionParam(param));
  }


  @CliCommand(value="add jar", help = "adds a jar resource to session")
  public String addJar(
      @CliOption(key={"","param"}, mandatory = true, help = "path to jar on serverside")
      String path){
    APIResult result = client.addJarResource(path);
    return result.getMessage();
  }

  @CliCommand(value="remove jar", help = "removes a jar resource from session")
  public String removeJar(
      @CliOption(key={"","param"}, mandatory = true, help = "path to jar on serverside")
      String path){
    APIResult result = client.removeJarResource(path);
    return result.getMessage();
  }

  @CliCommand(value="add file", help = "adds a file resource to session")
  public String addFile(
      @CliOption(key={"","param"}, mandatory = true, help = "path to file on serverside")
      String path){
    APIResult result = client.addFileResource(path);
    return result.getMessage();
  }

  @CliCommand(value="remove file", help = "removes a file resource from session")
  public String removeFile(
      @CliOption(key={"","param"}, mandatory = true, help = "path to file on serverside")
      String path){
    APIResult result = client.removeFileResource(path);
    return result.getMessage();
  }

  @CliCommand(value ={"close"}, help ="Exits the shell")
  public ExitShellRequest quitShell() {
    client.closeConnection();
    return ExitShellRequest.NORMAL_EXIT;
  }
}
