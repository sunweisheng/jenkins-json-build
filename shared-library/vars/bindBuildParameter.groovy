/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import hudson.model.ChoiceParameterDefinition
import hudson.model.Job
import hudson.model.ParameterDefinition
import hudson.model.ParametersDefinitionProperty
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 绑定下拉菜单方式的构建参数
 * @param paramName 构建参数名称
 * @param values 要绑定的数据
 */
void dropDownMenu(String paramName, String[] values) {
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}
	//获得Jenkins构建任务的定义
	Job job = $build().getParent()
	//获得Jenkins构建任务的构建参数定义
	ParametersDefinitionProperty paramsJobProperty = job.getProperty(ParametersDefinitionProperty.class) as ParametersDefinitionProperty
	if (paramsJobProperty != null) {
		//获得指定构建参数名称的构建参数对象（下拉菜单类型）
		ChoiceParameterDefinition choiceParam = (ChoiceParameterDefinition) paramsJobProperty.getParameterDefinition(paramName)
		//如果没有则添加此参数并绑定数据
		if (choiceParam == null) {
			choiceParam = new ChoiceParameterDefinition(paramName, values.toList(), '', '请选择要部署的项目')
			List<ParameterDefinition> jobParams = paramsJobProperty.getParameterDefinitions()
			jobParams.add(choiceParam)
		}
		else {
			//设置下拉菜单的数据
			choiceParam.setChoices(values.toList())
		}
		println('绑定下拉数据完成，最新的选择项是：')
		println(choiceParam.getChoices())
	}
	else {
		println("该构建任务不是参数化的构建任务")
	}
}

