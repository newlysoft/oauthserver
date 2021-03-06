package com.simon.controller;

import com.google.common.base.CaseFormat;
import com.simon.common.code.CodeGenerator;
import com.simon.common.code.Column;
import com.simon.common.code.EntityDataModel;
import com.simon.common.code.TableInfo;
import com.simon.common.controller.BaseController;
import com.simon.common.domain.ResultMsg;
import com.simon.common.domain.UserEntity;
import com.simon.common.utils.DbUtil;
import com.simon.dto.GenCodeDto;
import com.simon.model.ColumnUi;
import com.simon.service.ColumnUiService;
import com.simon.service.DictTypeService;
import com.simon.service.SideMenuService;
import com.simon.service.TableService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import springfox.documentation.annotations.ApiIgnore;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据表
 *
 * @author simon
 * @date 2018-10-06
 **/
@ApiIgnore
@Api(description = "数据表")
@Slf4j
@Controller
@RequestMapping("/api/tables")
public class TableController extends BaseController {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private DictTypeService dictTypeService;

    @Autowired
    private SideMenuService sideMenuService;

    @Autowired
    private ColumnUiService columnUiService;

    @Autowired
    private TableService tableService;

    @GetMapping("list")
    public String list(Model model) {
        return "vue/table/list";
    }

    @GetMapping("data")
    @ResponseBody
    public Object getTables(
            @ApiParam(value = "模糊查询表名") @RequestParam(required = false) String tableName,
            @ApiParam(value = "模糊查询表标注") @RequestParam(required = false) String tableComment,
            @ApiParam(value = "页码", defaultValue = "1", required = true) @RequestParam Integer pageNo,
            @ApiParam(value = "每页条数", defaultValue = "10", required = true) @RequestParam Integer pageSize) throws Exception {
        List<TableInfo> tableInfoList = DbUtil.getTables(CodeGenerator.JDBC_DIVER_CLASS_NAME, CodeGenerator.JDBC_URL, CodeGenerator.JDBC_USERNAME, CodeGenerator.JDBC_PASSWORD, tableName, tableComment);

        if (null != pageNo && null != pageSize) {
            Map<String, Object> resultMap = new HashMap<>(2);
            resultMap.put("total", tableInfoList.size());
            int toIndex = (pageNo - 1) * pageSize + pageSize;
            if (toIndex > tableInfoList.size()) {
                toIndex = tableInfoList.size();
            }
            resultMap.put("rows", tableInfoList.subList((pageNo - 1) * pageSize, toIndex));
            return resultMap;
        } else {
            return tableInfoList;
        }
    }

    /*@RequestMapping(value = "generate", method = RequestMethod.GET)
    @ResponseBody
    public ResultMsg generate(
            @RequestParam String tableName,
            @RequestParam String entityName,
            @ApiParam(value = "表id列类型", required = false, example = "Long") @RequestParam(required = false, defaultValue = "Long") String idType,
            @RequestParam(required = false) String genModules,
            Authentication authentication) {
        if (null != authentication) {
            UserEntity userEntity = getCurrentUser(authentication);
            CodeGenerator.genCodeByCustomModelName(tableName, entityName, idType, genModules, userEntity.getUsername());
        } else {
            CodeGenerator.genCodeByCustomModelName(tableName, entityName, idType, genModules);
        }
        return ResultMsg.success();
    }*/

    @GetMapping(value = "codeGenerate")
    public String codeGenerate(
            Model model,
            @RequestParam String tableName,
            @RequestParam(required = false) String tableComment,
            @RequestParam String entityName) {
        model.addAttribute("roleTypeList", listToMap(dictTypeService.getTypeByGroupCode("role_type")));
        model.addAttribute("parentMenus", listToMap(sideMenuService.getLevel1()));
        model.addAttribute("tableName", tableName);
        model.addAttribute("tableComment", tableComment);
        model.addAttribute("entityName", entityName);
        try {
            EntityDataModel entityDataModel = DbUtil.getEntityModel(dataSource.getConnection(), tableName, CodeGenerator.BASE_PACKAGE, entityName);
            List<ColumnUi> columnUiList = columnUiService.findByTableName(tableName);

            //想隐藏显示的列
            List<String> hiddenColumns = new ArrayList<>();
            hiddenColumns.add("createDate");
            hiddenColumns.add("createBy");
            hiddenColumns.add("updateDate");
            hiddenColumns.add("updateBy");

            //想不在页面上输入的列
            List<String> denyInputColumns = new ArrayList<>();
            denyInputColumns.add("createDate");
            denyInputColumns.add("createBy");
            denyInputColumns.add("updateDate");
            denyInputColumns.add("updateBy");

            for (Column column : entityDataModel.getColumns()) {
                //通用处理
                if (hiddenColumns.contains(column.getName())) {
                    column.setHidden(true);
                } else {
                    column.setHidden(false);
                }
                if (denyInputColumns.contains(column.getName())) {
                    column.setAllowInput(false);
                } else {
                    column.setAllowInput(true);
                }
                switch (column.getType()) {
                    case "Date":
                        //日期选择器
                        column.setUiType("DatePicker");
                        break;
                    case "Boolean":
                        //开关
                        column.setUiType("Switch");
                        break;
                    default:
                        break;
                }
                //加载用户上次生成代码时的配置
                for (ColumnUi columnUi : columnUiList) {
                    if (column.getName().equalsIgnoreCase(columnUi.getName())) {
                        column.setUiType(columnUi.getUiType());
                    }
                }
            }

            for (Column column : entityDataModel.getColumns()) {
                if ("id".equalsIgnoreCase(column.getName())) {
                    model.addAttribute("idType", column.getType());
                    break;
                }
            }

            model.addAttribute("tableEntity", entityDataModel);

        } catch (Exception e) {
            e.printStackTrace();
        }

        model.addAttribute("elementComponents", dictTypeService.getTypeByGroupCode("element_component"));
        return "vue/table/code_generate";
    }

    @RequestMapping(value = "genCode", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public ResultMsg genCode(@RequestBody GenCodeDto body, Authentication authentication) {
        UserEntity userEntity = getCurrentUser(authentication);
        List<Column> columnList = body.getColumns();
        EntityDataModel entityDataModel = new EntityDataModel();
        entityDataModel.setBasePackage(body.getBasePackage());
        entityDataModel.setEntityPackage(body.getBasePackage() + ".entity");
        entityDataModel.setFileSuffix(".java");
        entityDataModel.setEntityName(body.getEntityName());
        entityDataModel.setTableName(body.getTableName());
        entityDataModel.setTableComment(body.getTableComment());
        entityDataModel.setColumns(columnList);
        entityDataModel.setModelNameUpperCamel(body.getEntityName());
        entityDataModel.setModelNameLowerCamel(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, entityDataModel.getEntityName()));
        CodeGenerator.genCodeByCustomModelName(body.getMainOrTest(), body.getBasePackage(), body.getModuleDir(), body.getTableName(), body.getTableComment(), body.getEntityName(), body.getIdType(), StringUtils.join(body.getGenModules(), ","), userEntity.getUsername(), entityDataModel);

        //保存用户生成代码时的UI属性配置。
        //代码生成时，向t_side_menu表添加访问权限数据。
        tableService.saveSettingsAndAuthorities(body);
        return ResultMsg.success();
    }
}
