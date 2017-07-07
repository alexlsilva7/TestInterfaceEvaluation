package br.ufpe.cin.tan.test.ruby.routes

import br.ufpe.cin.tan.util.ConstantData


class RouteHelper {

    static routeIsFile(String name) {
        name.contains(".") && !name.contains("*")
    }

    static isViewFile(String name) {
        name.endsWith(ConstantData.ERB_EXTENSION) || name.endsWith(ConstantData.HAML_EXTENSION)
    }

    static routeIsAction(String name) {
        name.contains("#")
    }


}
